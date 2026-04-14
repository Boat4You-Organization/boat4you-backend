package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.dto.VivaCreateOrderRequestDto
import hr.workspace.boat4you.domains.reservation.dto.VivaCreateOrderResponseDto
import hr.workspace.boat4you.domains.reservation.dto.VivaCustomerDto
import hr.workspace.boat4you.domains.reservation.dto.VivaInitiateResponseDto
import hr.workspace.boat4you.domains.reservation.dto.VivaWebhookPayloadDto
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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

@Service
class VivaPaymentService(
    private val reservationRepository: ReservationRepository,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
    private val settingsService: AdminSettingsService,
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationEmailService: ReservationEmailService,
    private val vivaOAuthClient: VivaOAuthClient,
    @Value("\${application.viva.enabled}")
    private val vivaEnabled: Boolean,
    @Qualifier("vivaApiRestClient")
    private val vivaApiRestClient: RestClient,
    @Value("\${application.viva.checkout-url}")
    private val vivaCheckoutUrl: String,
    @Value("\${application.viva.source-code}")
    private val vivaSourceCode: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Transactional(readOnly = false)
    fun initiatePayment(
        reservationId: Long,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
    ): VivaInitiateResponseDto {
        checkIfVivaIsEnabled()

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

        val tags = mutableListOf("reservationId:$reservationId", "reservationFlowId:${reservationFlow.id!!}")
        if (payFullAmount != null) {
            tags.add("payFullAmount:$payFullAmount")
        }
        if (paymentPhaseId != null) {
            tags.add("paymentPhaseId:$paymentPhaseId")
        }

        val request =
            VivaCreateOrderRequestDto(
                amount = totalPriceInCents,
                sourceCode = vivaSourceCode,
                customerTrns = "Boat booking for reservation id: $reservationId",
                merchantTrns = reservationId.toString(),
                customer =
                    VivaCustomerDto(
                        email = reservationFlow.email,
                        fullName = reservationFlow.getFullName(),
                        requestLang = "en-GB",
                    ),
                tags = tags,
            )

        val bearer = vivaOAuthClient.getAccessToken()

        val createOrderResponse =
            try {
                vivaApiRestClient
                    .post()
                    .uri("/checkout/v2/orders")
                    .headers {
                        it.contentType = MediaType.APPLICATION_JSON
                        it.setBearerAuth(bearer)
                    }.body(request)
                    .retrieve()
                    .body(VivaCreateOrderResponseDto::class.java)
                    ?: error("Empty Viva create-order response")
            } catch (e: RestClientResponseException) {
                val body = e.responseBodyAsString
                logger.error("Viva create order failed: ${e.statusCode.value()} ${e.statusText} body=$body")
                throw IllegalStateException("Viva create order failed: ${e.statusText}", e)
            }

        val orderCode = createOrderResponse.orderCode
        setOrderCodeOnPaymentPhases(payFullAmount, paymentPhaseId, reservationFlow, orderCode)

        val redirectUrl = "$vivaCheckoutUrl?ref=$orderCode"
        logger.debug("Created Viva orderCode $orderCode for reservationId $reservationId")
        return VivaInitiateResponseDto(orderCode = orderCode, redirectUrl = redirectUrl)
    }

    @Transactional(readOnly = false)
    fun handleWebhookEvent(payload: VivaWebhookPayloadDto) {
        checkIfVivaIsEnabled()

        val orderCode = payload.eventData?.orderCode ?: return
        val transactionId = payload.eventData.transactionId!!

        logger.debug("Payment for Viva orderCode $orderCode received, transactionId=$transactionId")
        val reservationId = payload.getReservationIdTag()!!
        val payFullAmount = payload.getPayFullAmountTag()
        val paymentPhaseId = payload.getPaymentPhaseIdTag()

        val dbPaymentPhases = paymentPhaseRepository.findByVivaOrderCodeOrderByDeadlineAsc(orderCode)

        if (payFullAmount != null && payFullAmount) {
            dbPaymentPhases.forEach(setPaymentMetadata(transactionId))
        } else if (payFullAmount != null && !payFullAmount) {
            dbPaymentPhases.first().apply(setPaymentMetadata(transactionId))
        } else if (paymentPhaseId != null) {
            dbPaymentPhases.find { it.id == paymentPhaseId }!!.apply(setPaymentMetadata(transactionId))
        }

        logger.debug("Payment for Viva orderCode $orderCode successfully marked as paid, transactionId=$transactionId")

        // Reservation is created for the first time
        if (paymentPhaseId == null) {
            promoteReservationToBooking(reservationId)
        }
    }

    @Transactional(readOnly = true)
    fun checkPaymentStatus(orderCode: String): CheckoutSessionDto {
        checkIfVivaIsEnabled()
        val dbPaymentPhases = paymentPhaseRepository.findByVivaOrderCodeOrderByDeadlineAsc(orderCode)
        val paymentStatus =
            if (dbPaymentPhases.any { it.paidOn == null }) {
                CheckoutSessionStatusEnum.PAYMENT_PENDING
            } else {
                CheckoutSessionStatusEnum.PAYMENT_SUCCESS
            }

        return CheckoutSessionDto(sessionIdOrOrderCode = orderCode, status = paymentStatus)
    }

    private fun setOrderCodeOnPaymentPhases(
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        reservationFlow: ReservationFlow,
        orderCode: String,
    ) {
        when {
            payFullAmount == true -> reservationFlow.paymentPhases.forEach { it.vivaOrderCode = orderCode }
            payFullAmount == false -> reservationFlow.paymentPhases.oldest().vivaOrderCode = orderCode
            paymentPhaseId != null ->
                reservationFlow.paymentPhases
                    .find { it.id == paymentPhaseId }!!
                    .also { it.vivaOrderCode = orderCode }
        }
    }

    private fun setPaymentMetadata(transactionId: String): (ReservationPaymentPhase) -> Unit =
        {
            it.paidOn = Instant.now()
            it.vivaTransactionId = transactionId
        }

    private fun promoteReservationToBooking(reservationId: Long) {
        val externalReservation = reservationIntegrationService.confirmExternalReservation(reservationId)
        val reservationResponse = reservationMutationService.confirmReservation(reservationId, externalReservation)

        reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.CARD)
    }

    private fun BigDecimal.toCentsLong(): Long = this.setScale(2, RoundingMode.UP).times(100.toBigDecimal()).toLong()

    private fun checkIfVivaIsEnabled() {
        if (!vivaEnabled) error("Viva is not enabled in this environment")
    }

    private fun Set<ReservationPaymentPhase>.oldest(): ReservationPaymentPhase = this.minBy { it.deadline }
}
