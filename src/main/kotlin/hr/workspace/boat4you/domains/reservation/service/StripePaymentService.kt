package hr.workspace.boat4you.domains.reservation.service

import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
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
    // 3-letter ISO 4217. Stripe wants lowercase. EUR is the product default;
    // overridable per-env via APPLICATION_STRIPE_CURRENCY when an operator
    // prices in USD/GBP. Per-offer override would need a bigger change
    // (offer + phase currency fields) — that's a separate follow-up.
    @Value("\${application.stripe.currency:eur}") private val stripeCurrency: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Transactional(readOnly = false)
    fun initiatePayment(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        idempotencyKey: String? = null,
    ): Session {
        checkIfStripeIsEnabled()
        val dbReservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val reservationFlow = dbReservation.reservationFlow ?: throw ReservationNotExistException()

        if (payFullAmount == null && paymentPhaseId == null) {
            throw ParameterValidationException(mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter"))
        }

        // Phases that are NOT yet paid — the only valid targets for a fresh
        // Stripe charge. Admin-created replacement reservations may have the
        // first installment already marked paid (carry-over from a cancelled
        // booking), and an installment that was paid earlier on this same
        // reservation obviously must not be re-charged. Filtering here once
        // keeps the branches below simple.
        val unpaidPhases = reservationFlow.paymentPhases.filter { it.paidOn == null }

        val dbPrice: BigDecimal =
            when {
                // A specific phase was pinned from the UI (next-due or later
                // installment). Wins over `payFullAmount` if both are present.
                paymentPhaseId != null -> {
                    reservationFlow.paymentPhases.find { it.id == paymentPhaseId }?.amount
                        ?: throw ParameterValidationException(mapOf("paymentPhaseId" to "Payment phase not found"))
                }
                // "Pay full remaining" — sum of unpaid phases (NOT the full
                // reservation total, otherwise customers with a partially-paid
                // reservation would be overcharged).
                payFullAmount == true -> {
                    val remaining = unpaidPhases.sumOf { it.amount }
                    if (remaining > BigDecimal.ZERO) remaining
                    // Legacy fallback: if we have no phase rows at all yet
                    // (brand-new reservation, phases generated later), use
                    // the reservation total. This keeps the pre-installment
                    // flow intact for initial guest bookings.
                    else reservationFlow.calculatedTotalPrice!!
                }
                // "Pay first installment" — next DUE unpaid phase, not the
                // historically-oldest one which might already be paid.
                payFullAmount == false -> {
                    unpaidPhases.minByOrNull { it.deadline!! }?.amount
                        ?: throw ParameterValidationException(mapOf("paymentPhaseId" to "No unpaid payment phases remain"))
                }
                else -> throw ParameterValidationException(mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter"))
            }

        val cardSurchargePercentage = settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value?.toBigDecimal() ?: BigDecimal(0.0)
        val cardSurchargeAmount = dbPrice.times(cardSurchargePercentage.div(100.toBigDecimal()))
        val totalPriceInCents = (dbPrice + cardSurchargeAmount).toCentsLong()

        // The customer-facing booking number is the public payment reference
        // across all channels (bank transfer instructions, emails, Stripe).
        // Format: "1001{sequence}/{charter_year}", e.g. "100176/2026".
        // Using it as `client_reference_id` makes it the top-level identifier
        // in Stripe Dashboard search; `payment_intent_data.description`
        // pushes it onto the customer's bank card statement. Fallback to
        // reservation id for the (historical) case where no number was set.
        val paymentReference = dbReservation.reservationNumber ?: reservationId.toString()

        val params =
            SessionCreateParams
                .builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl("$serverHostPublic/payment-success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl("$serverHostPublic/payment-cancelled")
                .setClientReferenceId(paymentReference)
                .setPaymentIntentData(
                    SessionCreateParams.PaymentIntentData
                        .builder()
                        .setDescription("Boat4you reservation #$paymentReference")
                        .putMetadata("reservationId", reservationId.toString())
                        .putMetadata("reservationFlowId", reservationFlow.id!!.toString())
                        .build(),
                )
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
                                .setCurrency(stripeCurrency)
                                .setUnitAmount(totalPriceInCents)
                                .setProductData(
                                    SessionCreateParams.LineItem.PriceData.ProductData
                                        .builder()
                                        .setName("Boat Booking — Reservation #$paymentReference")
                                        .build(),
                                ).build(),
                        ).build(),
                ).build()

        // Stripe idempotency: when the frontend passes a key (generated once
        // per /payment page mount), Stripe dedupes retries for 24 h — a double
        // click returns the same Session instead of charging twice. Missing
        // key falls back to non-idempotent create so older callers still work.
        val session =
            if (idempotencyKey.isNullOrBlank()) {
                Session.create(params)
            } else {
                Session.create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build())
            }
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
