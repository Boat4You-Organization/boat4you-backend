package hr.workspace.boat4you.domains.payment.mollie.booking.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * One Mollie payment of an online-booking card payment (table `reservation_card_order`, V9_123; plan 2A.5). The
 * expected charge is frozen here at creation; the row is the dedupe source (reuse before create), the reconcile source
 * and the idempotent claim of the completion. Written only through `ReservationCardOrderTx`. Plain columns, no
 * relations: the phases are referenced by id in [phaseIds].
 */
@Entity
@Table(name = "reservation_card_order")
open class ReservationCardOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "provider", length = 16, nullable = false)
    open var provider: String = PROVIDER_MOLLIE

    @Column(name = "provider_order_id", length = 64, nullable = false, updatable = false)
    open var providerOrderId: String = ""

    /** `CARD` (cards + Apple Pay) or `KLARNA` (V9_124); NULL = a row from before Klarna = CARD. Read through [methodOrCard]. */
    @Column(name = "method", length = 16, updatable = false)
    open var method: String? = null

    /** Our token in the Mollie redirectUrl (`?paymentRef=`) and metadata; the success page asks the status with it. */
    @Column(name = "checkout_token", nullable = false, updatable = false)
    open var checkoutToken: UUID = UUID.randomUUID()

    @Column(name = "reservation_id")
    open var reservationId: Long? = null

    @Column(name = "reservation_flow_id", nullable = false)
    open var reservationFlowId: Long = 0

    /** Sorted, comma-separated phase ids ([phaseIdsOf]); empty = legacy pay-full without phase rows. */
    @Column(name = "phase_ids", length = 512, nullable = false)
    open var phaseIds: String = ""

    @Column(name = "pay_full_amount")
    open var payFullAmount: Boolean? = null

    /** As requested by the web; NULL = the pay-full path (promote to booking after payment, as Stripe). */
    @Column(name = "payment_phase_id")
    open var paymentPhaseId: Long? = null

    @Column(name = "base_amount", precision = 12, scale = 2, nullable = false)
    open var baseAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "surcharge_amount", precision = 12, scale = 2, nullable = false)
    open var surchargeAmount: BigDecimal = BigDecimal.ZERO

    /** Cents sent to Mollie = `CardChargeCalculator.toCentsLong(base + surcharge)`. */
    @Column(name = "amount_minor", nullable = false)
    open var amountMinor: Long = 0

    @Column(name = "currency", length = 3, nullable = false)
    open var currency: String = CURRENCY_EUR

    /** Reservation number (sanitised); in the Mollie description and metadata. */
    @Column(name = "reference", length = 64, nullable = false)
    open var reference: String = ""

    @Column(name = "idempotency_key", length = 128)
    open var idempotencyKey: String? = null

    @Column(name = "checkout_url", nullable = false)
    open var checkoutUrl: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    open var status: ReservationCardOrderStatus = ReservationCardOrderStatus.PENDING

    @Column(name = "review_reason")
    open var reviewReason: String? = null

    @Column(name = "provider_payment_id", length = 64)
    open var providerPaymentId: String? = null

    /** Mollie's running refund total (cents); alerts the office when it grows. */
    @Column(name = "refunded_amount_minor", nullable = false)
    open var refundedAmountMinor: Long = 0

    /** Mollie's running chargeback total (cents); alerts the office when it grows. */
    @Column(name = "charged_back_amount_minor", nullable = false)
    open var chargedBackAmountMinor: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "promote_state", length = 16)
    open var promoteState: CardOrderPromoteState? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant = Instant.now()

    /** Effective `expire_pending_after` of the order (plan 2A.10 M3); the reuse window is computed from it. */
    @Column(name = "expires_at", nullable = false)
    open var expiresAt: Instant = Instant.now()

    @Column(name = "completed_at")
    open var completedAt: Instant? = null

    @Column(name = "last_checked_at")
    open var lastCheckedAt: Instant? = null

    @Column(name = "last_webhook_at")
    open var lastWebhookAt: Instant? = null

    @Column(name = "last_webhook_event", length = 64)
    open var lastWebhookEvent: String? = null

    val methodOrCard: BookingCheckoutMethod get() = BookingCheckoutMethod.fromStored(method)

    /** [phaseIds] as numbers (empty for the legacy pay-full fallback). */
    fun phaseIdList(): List<Long> = phaseIds.split(",").filter { it.isNotEmpty() }.map { it.toLong() }

    companion object {
        const val CURRENCY_EUR = "EUR"
        const val PROVIDER_MOLLIE = "MOLLIE"

        /** The canonical `phase_ids` value: distinct ids, ascending, comma-separated. */
        fun phaseIdsOf(ids: Collection<Long>): String = ids.distinct().sorted().joinToString(",")
    }
}
