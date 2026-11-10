package hr.workspace.boat4you.domains.payment.mollie.admin

import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.CardOrderPromoteState
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import org.springframework.data.web.PagedModel
import java.time.Instant

/** Admin "Payments" list, tab "Booking payments": one Mollie payment (checkout attempt) of an online booking. */
data class AdminCardPaymentDto(
    val id: Long,
    val createdAt: Instant,
    val completedAt: Instant?,
    val expiresAt: Instant,
    val status: ReservationCardOrderStatus,
    val reservationId: Long?,
    val reservationNumber: String?,
    /** Booking number as sent to Mollie; the label when the reservation itself is gone. */
    val reference: String,
    val clientName: String?,
    val clientEmail: String?,
    val clientPhone: String?,
    /** Instalment amount without the card surcharge, cents. */
    val baseAmountMinor: Long,
    val surchargeAmountMinor: Long,
    /** Charged = base + surcharge, cents. */
    val amountMinor: Long,
    val refundedAmountMinor: Long,
    val chargedBackAmountMinor: Long,
    val currency: String,
    /** Mollie payment id (`tr_…`). */
    val providerOrderId: String,
    val payFullAmount: Boolean,
    val phaseIds: List<Long>,
    val reviewReason: String?,
    val promoteState: CardOrderPromoteState?,
    val lastCheckedAt: Instant?,
)

/** Same `content` / `page` shape as the custom payments list + money kept among the filtered rows. */
data class AdminCardPaymentPageDto(
    val content: List<AdminCardPaymentDto>,
    val page: PagedModel.PageMetadata,
    /** Cents: COMPLETED rows, charged minus refunded minus charged back, over the same filters (all pages). */
    val totalReceivedMinor: Long,
)

/**
 * `GET /admin/mollie-payments/{id}`: one Mollie payment as Mollie reports it right now (booking payments and `/pay`
 * payments). Mollie's fee and the net payout are not here: the API key cannot read balances or settlements.
 */
data class AdminMolliePaymentDto(
    val id: String,
    val mode: String?,
    /** Mollie's own status: open | pending | authorized | paid | canceled | expired | failed. */
    val status: String,
    val method: String?,
    val description: String?,
    val amountMinor: Long,
    val refundedAmountMinor: Long,
    val chargedBackAmountMinor: Long,
    val currency: String,
    val createdAt: Instant?,
    val paidAt: Instant?,
    val expiresAt: Instant?,
    val expiredAt: Instant?,
    val failedAt: Instant?,
    val canceledAt: Instant?,
    val cardLabel: String?,
    val cardLast4: String?,
    val cardCountryCode: String?,
    val cardFunding: String?,
    val cardAudience: String?,
    val feeRegion: String?,
    val failureReason: String?,
    val failureMessage: String?,
    val refunds: List<AdminMollieRefundDto>,
    val chargebacks: List<AdminMollieChargebackDto>,
)

data class AdminMollieRefundDto(
    val id: String,
    val amountMinor: Long,
    val status: String,
    val createdAt: Instant?,
    val description: String?,
)

data class AdminMollieChargebackDto(
    val id: String,
    val amountMinor: Long,
    val createdAt: Instant?,
    val reason: String?,
)
