package hr.workspace.boat4you.domains.payment.mollie.admin

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.payment.mollie.MollieClient
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingPaymentService
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.reservation.service.CardChargeCalculator
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentNotFoundException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.PagedModel
import org.springframework.stereotype.Service

/**
 * Admin view of the Mollie payments: the list of online-booking card payments (ledger `reservation_card_order`), their
 * Refresh, and the live details of one Mollie payment. Read-only towards Mollie: nothing here creates, cancels or
 * refunds a payment.
 */
@Service
class AdminCardPaymentService(
    private val orders: ReservationCardOrderRepository,
    private val customPayments: CustomPaymentRepository,
    private val bookingPayments: MollieBookingPaymentService,
    private val client: MollieClient,
    private val properties: MollieProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(
        statuses: List<ReservationCardOrderStatus>?,
        q: String?,
        reservationId: Long?,
        page: Int,
        size: Int,
    ): AdminCardPaymentPageDto {
        val statusFilter = statuses?.takeIf { it.isNotEmpty() } ?: ReservationCardOrderStatus.entries
        val reservationFilter = reservationId ?: ANY_RESERVATION
        val search = q?.trim()?.ifEmpty { null }
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        val result = orders.searchForAdmin(statusFilter, reservationFilter, search, pageable)
        return AdminCardPaymentPageDto(
            content = result.content.map { it.toDto() },
            page = PagedModel.PageMetadata(result.size.toLong(), result.number.toLong(), result.totalElements, result.totalPages.toLong()),
            totalReceivedMinor = orders.sumReceivedForAdmin(statusFilter, reservationFilter, search, ReservationCardOrderStatus.COMPLETED),
        )
    }

    /** Re-reads the payment from Mollie, applies it like the webhook, and returns the row as the list shows it. */
    fun refresh(id: Long): AdminCardPaymentDto {
        bookingPayments.refresh(id)
        return orders.findAdminRowById(id).firstOrNull()?.toDto() ?: throw CardPaymentNotFoundException()
    }

    /**
     * One of OUR Mollie payments (a booking payment or a `/pay` payment) as Mollie reports it now, with its refunds and
     * chargebacks. An id that is not in either table is a 404: the endpoint is no window into the whole Mollie account.
     */
    fun mollieDetails(paymentId: String): AdminMolliePaymentDto {
        if (!MollieClient.isValidPaymentId(paymentId)) throw CardPaymentNotFoundException()
        if (!orders.existsByProviderOrderId(paymentId) && !customPayments.existsByProviderOrderId(paymentId)) throw CardPaymentNotFoundException()
        if (!properties.enabled) throw CardPaymentsUnavailableException()
        val payment =
            try {
                client.getPaymentWithMoneyBack(paymentId)
            } catch (e: Exception) {
                log.warn("Mollie payment {}: admin details could not be read ({})", paymentId, e.javaClass.simpleName)
                throw CardPaymentProviderException()
            } ?: throw CardPaymentProviderException()
        return payment.toDto()
    }

    private fun Array<Any?>.toDto(): AdminCardPaymentDto {
        val order = this[ORDER] as ReservationCardOrder
        val name = listOfNotNull(this[NAME] as String?, this[SURNAME] as String?).joinToString(" ") { it.trim() }.trim()
        return AdminCardPaymentDto(
            id = order.id!!,
            createdAt = order.createdAt,
            completedAt = order.completedAt,
            expiresAt = order.expiresAt,
            status = order.status,
            method = order.methodOrCard,
            reservationId = order.reservationId,
            reservationNumber = this[RESERVATION_NUMBER] as String?,
            reference = order.reference,
            clientName = name.ifEmpty { null },
            clientEmail = (this[EMAIL] as String?)?.trim()?.ifEmpty { null },
            clientPhone = (this[PHONE] as String?)?.trim()?.ifEmpty { null },
            baseAmountMinor = CardChargeCalculator.toCentsLong(order.baseAmount),
            surchargeAmountMinor = CardChargeCalculator.toCentsLong(order.surchargeAmount),
            amountMinor = order.amountMinor,
            refundedAmountMinor = order.refundedAmountMinor,
            chargedBackAmountMinor = order.chargedBackAmountMinor,
            currency = order.currency,
            providerOrderId = order.providerOrderId,
            payFullAmount = order.payFullAmount == true,
            phaseIds = order.phaseIdList(),
            reviewReason = order.reviewReason,
            promoteState = order.promoteState,
            lastCheckedAt = order.lastCheckedAt,
        )
    }

    private fun MolliePayment.toDto() =
        AdminMolliePaymentDto(
            id = id,
            mode = mode,
            status = status.lowercase(),
            method = method,
            description = description,
            amountMinor = amount.minor(),
            refundedAmountMinor = amountRefunded.minor(),
            chargedBackAmountMinor = amountChargedBack.minor(),
            currency = amount.currency,
            createdAt = createdAt,
            paidAt = paidAt,
            expiresAt = expiresAt,
            expiredAt = expiredAt,
            failedAt = failedAt,
            canceledAt = canceledAt,
            cardLabel = details?.cardLabel,
            cardLast4 = details?.cardNumber?.takeIf { LAST_FOUR.matches(it) },
            cardCountryCode = details?.cardCountryCode,
            cardFunding = details?.cardFunding,
            cardAudience = details?.cardAudience,
            feeRegion = details?.feeRegion,
            failureReason = details?.failureReason,
            failureMessage = details?.failureMessage,
            refunds =
                embedded?.refunds.orEmpty().map {
                    AdminMollieRefundDto(it.id, it.amount.minor(), it.status?.lowercase() ?: "unknown", it.createdAt, it.description)
                },
            chargebacks =
                embedded?.chargebacks.orEmpty().map {
                    AdminMollieChargebackDto(it.id, it.amount.minor(), it.createdAt, it.reason?.description ?: it.reason?.code)
                },
        )

    private fun MollieAmount?.minor(): Long = this?.toMinorOrNull() ?: 0

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val ANY_RESERVATION = -1L
        const val ORDER = 0
        const val NAME = 1
        const val SURNAME = 2
        const val EMAIL = 3
        const val PHONE = 4
        const val RESERVATION_NUMBER = 5
        val LAST_FOUR = Regex("^[0-9]{4}$")
    }
}
