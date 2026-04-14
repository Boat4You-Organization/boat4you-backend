package hr.workspace.boat4you.domains.reservation.service

import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.param.checkout.SessionCreateParams
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

@Service
class StripePaymentService(
    private val reservationRepository: ReservationRepository,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
    private val settingsService: AdminSettingsService,
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationEmailService: ReservationEmailService,
    @Value("\${server.host-public}") private val serverHostPublic: String,
    @Value("\${application.stripe.enabled}") private val stripeEnabled: Boolean,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Transactional(readOnly = false)
    fun initiatePayment(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
    ): Session {
        checkIfStripeIsEnabled()
        val dbReservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val reservationFlow = dbReservation.reservationFlow ?: throw ReservationNotExistException()

        if (payFullAmount == null && paymentPhaseId == null) {
            throw ParameterValidationException(mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter"))
        }

        val dbPrice: BigDecimal =
            // Reservation created for the first time
            if (payFullAmount != null) {
                if (payFullAmount) {
                    reservationFlow.calculatedTotalPrice!!
                } else {
                    reservationFlow.paymentPhases.oldest().amount
                }
            }
            // Another installment being paid later
            else {
                reservationFlow.paymentPhases.find { it.id == paymentPhaseId }?.amount ?: throw ParameterValidationException(mapOf("paymentPhaseId" to "Payment phase not found"))
            }

        val cardSurchargePercentage = settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value?.toBigDecimal() ?: BigDecimal(0.0)
        val cardSurchargeAmount = dbPrice.times(cardSurchargePercentage.div(100.toBigDecimal()))
        val totalPriceInCents = (dbPrice + cardSurchargeAmount).toCentsLong()

        val params =
            SessionCreateParams
                .builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl("$serverHostPublic/payment-success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl("$serverHostPublic/payment-cancelled")
                .putMetadata("reservationId", reservationId.toString())
                .putMetadata("reservationFlowId", reservationFlow.id!!.toString())
                .apply {
                    payFullAmount?.let { putMetadata("payFullAmount", it.toString()) }
                    paymentPhaseId?.let { putMetadata("paymentPhaseId", it.toString()) }
                }.addLineItem(
                    SessionCreateParams.LineItem
                        .builder()
                        .setQuantity(1L)
                        .setPriceData(
                            SessionCreateParams.LineItem.PriceData
                                .builder()
                                .setCurrency("eur")
                                .setUnitAmount(totalPriceInCents)
                                .setProductData(
                                    SessionCreateParams.LineItem.PriceData.ProductData
                                        .builder()
                                        .setName("Boat Booking for Reservation id: $reservationId")
                                        .build(),
                                ).build(),
                        ).build(),
                ).build()

        val session = Session.create(params)
        setSessionIdOnPaymentPhases(payFullAmount, paymentPhaseId, reservationFlow, session.id)

        logger.debug("Created Stripe sessionId ${session.id} for reservationId $reservationId")
        return session
    }

    @Transactional(readOnly = false)
    fun handleWebhookEvent(event: Event) {
        checkIfStripeIsEnabled()
        val session = event.dataObjectDeserializer.`object`.orElse(null) as? Session

        if (event.type == "checkout.session.completed") {
            // Session object always exists on a successful payment?
            logger.debug("Payment for Stripe sessionId ${session!!.id} successful")

            val paymentIntentId = session.paymentIntent

            val reservationId = session.metadata["reservationId"]!!.toLong()
            val payFullAmount = session.metadata["payFullAmount"]?.toBoolean()
            val paymentPhaseId = session.metadata["paymentPhaseId"]?.toLong()

            val dbPaymentPhases = paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(session.id)

            if (payFullAmount != null && payFullAmount) {
                dbPaymentPhases.forEach(setPaymentMetadata(paymentIntentId))
            } else if (payFullAmount != null && !payFullAmount) {
                dbPaymentPhases.first().apply(setPaymentMetadata(paymentIntentId))
            } else if (paymentPhaseId != null) {
                dbPaymentPhases.find { it.id == paymentPhaseId }!!.apply(setPaymentMetadata(paymentIntentId))
            }

            // Reservation is created for the first time
            if (paymentPhaseId == null) {
                promoteReservationToBooking(reservationId)
            }
        } else {
            logger.error("Payment for Stripe sessionId ${session?.id} failed. Stripe event id: ${event.id}")
        }
    }

    @Transactional(readOnly = true)
    fun checkPaymentStatus(stripeSessionId: String): CheckoutSessionDto {
        checkIfStripeIsEnabled()
        val dbPaymentPhases = paymentPhaseRepository.findByStripeSessionIdOrderByDeadlineAsc(stripeSessionId)
        val paymentStatus =
            if (dbPaymentPhases.any { it.paidOn == null }) {
                CheckoutSessionStatusEnum.PAYMENT_PENDING
            } else {
                CheckoutSessionStatusEnum.PAYMENT_SUCCESS
            }

        return CheckoutSessionDto(sessionIdOrOrderCode = stripeSessionId, status = paymentStatus)
    }

    private fun setSessionIdOnPaymentPhases(
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        reservationFlow: ReservationFlow,
        sessionId: String,
    ) {
        if (payFullAmount != null && payFullAmount) {
            reservationFlow.paymentPhases.forEach { it.stripeSessionId = sessionId }
        } else if (payFullAmount != null && !payFullAmount) {
            reservationFlow.paymentPhases.oldest().stripeSessionId = sessionId
        } else if (paymentPhaseId != null) {
            reservationFlow.paymentPhases.find { it.id == paymentPhaseId }!!.stripeSessionId = sessionId
        }
    }

    private fun setPaymentMetadata(paymentIntentId: String): (ReservationPaymentPhase) -> Unit =
        {
            it.paidOn = Instant.now()
            it.stripePaymentIntentId = paymentIntentId
        }

    private fun promoteReservationToBooking(reservationId: Long) {
        val externalReservation = reservationIntegrationService.confirmExternalReservation(reservationId)
        val reservationResponse = reservationMutationService.confirmReservation(reservationId, externalReservation)

        reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.CARD)
    }

    private fun BigDecimal.toCentsLong(): Long = this.setScale(2, RoundingMode.UP).times(100.toBigDecimal()).toLong()

    private fun checkIfStripeIsEnabled() {
        if (!stripeEnabled) {
            throw RuntimeException("Stripe is not enabled in this environment")
        }
    }

    private fun Set<ReservationPaymentPhase>.oldest(): ReservationPaymentPhase = this.minBy { it.deadline }
}
