package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** What a new booking checkout charges, frozen on its ledger row ([ReservationCardOrderTx.insertPending]). */
data class BookingOrderPlan(
    val reservationId: Long,
    val reservationFlowId: Long,
    /** `merchant_order_data.reference` and the description's `#<ref>`: reservation number (or id), sanitised. */
    val reference: String,
    /** Canonical `phase_ids` ([ReservationCardOrder.phaseIdsOf]). */
    val phaseIds: String,
    val payFullAmount: Boolean?,
    val paymentPhaseId: Long?,
    val baseAmount: BigDecimal,
    val surchargeAmount: BigDecimal,
    val amountMinor: Long,
    val idempotencyKey: String?,
    /** `expire_pending_after` sent to Revolut ([BookingOrderExpiry]). */
    val expiresAfter: Duration,
    /** Planning time + [expiresAfter] (never later than Revolut's own expiry: the order is created after it). */
    val expiresAt: Instant,
)

/** [order] is the new PENDING row, or the reusable row a concurrent checkout inserted first ([existing]). */
data class PendingInsert(
    val order: ReservationCardOrder,
    val existing: Boolean,
)

/**
 * Writes of `reservation_card_order`, each in its own short transaction (database only — Revolut is called by
 * [RevolutBookingPaymentService] between these calls, never inside one). Stage C adds `applyCompleted` here.
 */
@Service
@Transactional
class ReservationCardOrderTx(
    private val orders: ReservationCardOrderRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The ledger row of a just created Revolut order. Under the reservation flow's advisory lock (the same one the
     * create transaction holds): when a concurrent checkout for the same charge inserted its row first, that row is
     * returned instead (`existing=true`) and the caller cancels its own order — one checkout per charge.
     */
    fun insertPending(
        plan: BookingOrderPlan,
        providerOrderId: String,
        checkoutUrl: String,
    ): PendingInsert {
        orders.setLocalLockTimeout()
        orders.lockKey(flowLockKey(plan.reservationFlowId))
        orders
            .findReusable(plan.reservationFlowId, plan.phaseIds, plan.amountMinor, paymentPhaseKey(plan.paymentPhaseId), reusableUntil())
            ?.let { return PendingInsert(it, existing = true) }
        val row =
            ReservationCardOrder().apply {
                this.providerOrderId = providerOrderId
                reservationId = plan.reservationId
                reservationFlowId = plan.reservationFlowId
                phaseIds = plan.phaseIds
                payFullAmount = plan.payFullAmount
                paymentPhaseId = plan.paymentPhaseId
                baseAmount = plan.baseAmount
                surchargeAmount = plan.surchargeAmount
                amountMinor = plan.amountMinor
                reference = plan.reference
                idempotencyKey = plan.idempotencyKey
                this.checkoutUrl = checkoutUrl
                expiresAt = plan.expiresAt
            }
        return PendingInsert(orders.saveAndFlush(row), existing = false)
    }

    /**
     * Revolut says the order ended without a payment ([target] = CANCELLED or FAILED): only a PENDING row changes.
     * Returns the status after the call (null for an unknown id).
     */
    fun closeIfPending(
        id: Long,
        target: ReservationCardOrderStatus,
    ): ReservationCardOrderStatus? {
        require(target == ReservationCardOrderStatus.CANCELLED || target == ReservationCardOrderStatus.FAILED) { "Not a closing status: $target" }
        orders.setLocalLockTimeout()
        val row = orders.lockById(id) ?: return null
        row.lastCheckedAt = Instant.now()
        if (row.status == ReservationCardOrderStatus.PENDING) {
            row.status = target
            log.info("Revolut booking order {} (ledger {}) {}", row.providerOrderId, row.id, target)
        }
        return row.status
    }

    companion object {
        /** A PENDING order is reused only while Revolut keeps it open for at least this long. */
        val REUSE_MARGIN: Duration = Duration.ofMinutes(10)

        /** `payment_phase_id` key of [ReservationCardOrderRepository.findReusable] (NULL = the pay-full path). */
        private const val NO_PAYMENT_PHASE = -1L

        fun reusableUntil(): Instant = Instant.now().plus(REUSE_MARGIN)

        fun paymentPhaseKey(paymentPhaseId: Long?): Long = paymentPhaseId ?: NO_PAYMENT_PHASE

        /** Advisory lock key serialising the checkouts of one reservation flow. */
        fun flowLockKey(reservationFlowId: Long): String = "reservation-card-order|$reservationFlowId"
    }
}
