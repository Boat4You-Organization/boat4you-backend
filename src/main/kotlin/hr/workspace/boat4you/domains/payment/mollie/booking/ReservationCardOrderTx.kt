package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentStatuses
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.CardOrderPromoteState
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.events.ReservationPaymentRecordedEvent
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import org.springframework.context.ApplicationEventPublisher
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
    /** Reservation number (or id), sanitised: Mollie description `#<ref>` and metadata. */
    val reference: String,
    /** Canonical `phase_ids` ([ReservationCardOrder.phaseIdsOf]). */
    val phaseIds: String,
    val payFullAmount: Boolean?,
    val paymentPhaseId: Long?,
    val baseAmount: BigDecimal,
    val surchargeAmount: BigDecimal,
    val amountMinor: Long,
    val idempotencyKey: String?,
    /** Our token in the redirectUrl / metadata (`checkout_token`). */
    val checkoutToken: java.util.UUID,
    /** How many earlier checkouts of the flow used the same web idempotency key (0 = the first). */
    val attempt: Long = 0,
    /** Our checkout lifetime ([MollieBookingProperties.checkoutTtl]). */
    val expiresAfter: Duration,
    /** Planning time + [expiresAfter]. */
    val expiresAt: Instant,
)

/**
 * Result of [ReservationCardOrderTx.applyCompleted]. [completedNow] = THIS call moved the row to COMPLETED and marked
 * the phases; [promote] = the pay-full / first-payment path (no paymentPhaseId), whose booking confirmation runs AFTER
 * the commit (M4); [alert] = a reason the office must look at (NEEDS_REVIEW), mailed after the commit.
 */
data class CompletionResult(
    val completedNow: Boolean,
    val promote: Boolean,
    val reservationId: Long?,
    val alert: String? = null,
) {
    companion object {
        val NOTHING = CompletionResult(completedNow = false, promote = false, reservationId = null)
    }
}

/** [order] is the new PENDING row, or the reusable row a concurrent checkout inserted first ([existing]). */
data class PendingInsert(
    val order: ReservationCardOrder,
    val existing: Boolean,
)

/**
 * Writes of `reservation_card_order`, each in its own short transaction (database only — Mollie is called by
 * [MollieBookingPaymentService] between these calls, never inside one).
 */
@Service
@Transactional
class ReservationCardOrderTx(
    private val orders: ReservationCardOrderRepository,
    private val phaseRepository: ReservationPaymentPhaseRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val reservationRepository: ReservationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The ledger row of a just created Mollie payment. Under the reservation flow's advisory lock (the same one the
     * create transaction holds): when a concurrent checkout for the same charge inserted its row first, that row is
     * returned instead (`existing=true`) and the caller cancels its own order — one checkout per charge.
     * The same Mollie payment again (Idempotency-Key replay of a double click): its row is the answer, whatever its
     * status — the caller hands out only a PENDING one.
     */
    fun insertPending(
        plan: BookingOrderPlan,
        providerOrderId: String,
        checkoutUrl: String,
    ): PendingInsert {
        orders.setLocalLockTimeout()
        orders.lockKey(flowLockKey(plan.reservationFlowId))
        orders.findByProviderOrderId(providerOrderId)?.let { return PendingInsert(it, existing = true) }
        orders
            .findReusable(plan.reservationFlowId, plan.phaseIds, plan.amountMinor, paymentPhaseKey(plan.paymentPhaseId), reusableUntil())
            ?.let { return PendingInsert(it, existing = true) }
        val row =
            ReservationCardOrder().apply {
                this.providerOrderId = providerOrderId
                provider = ReservationCardOrder.PROVIDER_MOLLIE
                checkoutToken = plan.checkoutToken
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
     * A newer checkout of the same flow replaces this PENDING payment on our side; Mollie may still take it (cards cannot
     * be cancelled), so the row stays readable (status endpoint, reconcile) until Mollie closes or pays it.
     */
    fun markSuperseded(id: Long): ReservationCardOrderStatus? {
        orders.setLocalLockTimeout()
        val row = orders.lockById(id) ?: return null
        row.lastCheckedAt = Instant.now()
        if (row.status == ReservationCardOrderStatus.PENDING) {
            row.status = ReservationCardOrderStatus.SUPERSEDED
            log.info("Mollie booking payment {} (ledger {}) superseded by a newer checkout", row.providerOrderId, row.id)
        }
        return row.status
    }

    /**
     * Mollie says the payment ended without money ([target] = CANCELLED or FAILED): only an open row (PENDING or
     * SUPERSEDED) changes. Returns the status after the call (null for an unknown id).
     */
    fun closeIfPending(
        id: Long,
        target: ReservationCardOrderStatus,
    ): ReservationCardOrderStatus? {
        require(target == ReservationCardOrderStatus.CANCELLED || target == ReservationCardOrderStatus.FAILED) { "Not a closing status: $target" }
        orders.setLocalLockTimeout()
        val row = orders.lockById(id) ?: return null
        row.lastCheckedAt = Instant.now()
        if (row.status == ReservationCardOrderStatus.PENDING || row.status == ReservationCardOrderStatus.SUPERSEDED) {
            row.status = target
            log.info("Mollie booking order {} (ledger {}) {}", row.providerOrderId, row.id, target)
        }
        return row.status
    }

    /**
     * Records a payment Mollie reports as `paid` ([payment] was just READ from the Mollie API — never a webhook body)
     * for the ledger row [id], in this one transaction (M2: never inside the create transaction):
     * - a row already COMPLETED / NEEDS_REVIEW -> nothing (idempotent: webhook, status endpoint and reconcile may race);
     * - amount, currency or the flow / phases in the metadata not matching -> NEEDS_REVIEW + alert, phases untouched;
     * - a target phase already paid (Stripe, admin, another payment) -> NEEDS_REVIEW + alert "double payment";
     * - otherwise every target phase gets paidOn + provider ids, the row COMPLETED, ReservationPaymentRecordedEvent.
     * A row closed earlier (CANCELLED / FAILED: superseded or expired on our side) that Mollie still reports paid is
     * recorded the same way — the money is real. The booking confirmation of the first payment runs after the
     * commit ([CompletionResult.promote], M4).
     */
    fun applyCompleted(
        id: Long,
        payment: MolliePayment,
        event: String?,
    ): CompletionResult {
        orders.setLocalLockTimeout()
        val row = orders.lockById(id) ?: return CompletionResult.NOTHING
        stamp(row, event)
        if (row.status == ReservationCardOrderStatus.COMPLETED || row.status == ReservationCardOrderStatus.NEEDS_REVIEW) return CompletionResult.NOTHING
        if (payment.status.lowercase() != MolliePaymentStatuses.PAID || payment.id != row.providerOrderId) return CompletionResult.NOTHING

        val mismatches =
            listOfNotNull(
                "amount ${payment.amount.value} instead of ${row.amountMinor} cents".takeIf { payment.amount.toMinorOrNull() != row.amountMinor },
                "currency ${payment.amount.currency}".takeIf { !payment.amount.currency.equals(ReservationCardOrder.CURRENCY_EUR, ignoreCase = true) },
                "reservation flow / phases in the metadata do not match".takeIf {
                    payment.metadataString(FLOW_METADATA_KEY) != row.reservationFlowId.toString() ||
                        (payment.metadataString(PHASES_METADATA_KEY) ?: "") != row.phaseIds
                },
            )
        if (mismatches.isNotEmpty()) return review(row, "Mollie payment paid but " + mismatches.joinToString(", "))

        val reservation = row.reservationId?.let { reservationRepository.findById(it).orElse(null) }
        if (reservation == null || reservation.sysStatus == ReservationStatus.CANCELLED) {
            return review(row, "paid, but reservation ${row.reservationId ?: "-"} is cancelled or deleted — refund or rebook")
        }

        val phaseIds = row.phaseIdList()
        if (phaseIds.isEmpty() && orders.countOtherCompleted(row.reservationFlowId, row.id!!) > 0) {
            return review(row, "paid, but another payment of this booking is already COMPLETED — DOUBLE PAYMENT, refund in Mollie")
        }
        if (phaseIds.isNotEmpty()) {
            val phases = phaseRepository.findAllByIdIn(phaseIds)
            if (phases.size != phaseIds.size) return review(row, "paid, but phase(s) ${row.phaseIds} no longer exist")
            if (phaseRepository.countByIdInAndPaidOnIsNotNull(phaseIds) > 0) {
                return review(row, "paid, but a phase of ${row.phaseIds} was already paid — DOUBLE PAYMENT, refund in Mollie")
            }
            val now = Instant.now()
            phases.forEach { phase ->
                phase.paidOn = now
                phase.paymentProvider = ReservationCardOrder.PROVIDER_MOLLIE
                phase.providerOrderId = payment.id
                phase.providerPaymentId = payment.id
            }
        }
        val previous = row.status
        row.status = ReservationCardOrderStatus.COMPLETED
        row.completedAt = Instant.now()
        row.providerPaymentId = payment.id
        val promote = row.paymentPhaseId == null
        if (promote) row.promoteState = CardOrderPromoteState.PENDING
        log.info("Mollie booking order {} (ledger {}) COMPLETED (was {}), phases [{}], promote={}", row.providerOrderId, row.id, previous, row.phaseIds, promote)
        row.reservationId?.let { eventPublisher.publishEvent(ReservationPaymentRecordedEvent(it)) }
        return CompletionResult(completedNow = true, promote = promote, reservationId = row.reservationId)
    }

    /**
     * Refunds and chargebacks of a paid payment: Mollie's running totals only ever raise the stored ones. Returns the
     * alert reason when a total grew (the phases stay as they are — a person decides what the refund means).
     */
    fun applyMoneyBack(
        id: Long,
        payment: MolliePayment,
    ): String? {
        val row = orders.lockById(id) ?: return null
        val refunded = (payment.amountRefunded?.toMinorOrNull() ?: 0L).coerceIn(0L, row.amountMinor)
        val chargedBack = (payment.amountChargedBack?.toMinorOrNull() ?: 0L).coerceIn(0L, row.amountMinor)
        val reasons = mutableListOf<String>()
        if (refunded > row.refundedAmountMinor) {
            row.refundedAmountMinor = refunded
            reasons += "refunded $refunded of ${row.amountMinor} cents (the phases are still marked paid — adjust the booking)"
        }
        if (chargedBack > row.chargedBackAmountMinor) {
            row.chargedBackAmountMinor = chargedBack
            reasons += "CHARGEBACK $chargedBack of ${row.amountMinor} cents — answer the dispute in the Mollie dashboard"
        }
        if (reasons.isEmpty()) return null
        log.warn("Mollie booking payment {} (ledger {}): {}", row.providerOrderId, row.id, reasons.joinToString("; "))
        return reasons.joinToString("; ")
    }

    /** Reconcile: still open on our side 48 h after creation -> NEEDS_REVIEW (true = changed now, alert once). */
    fun markStaleOpen(id: Long): Boolean {
        val row = orders.lockById(id) ?: return false
        if (row.status != ReservationCardOrderStatus.PENDING && row.status != ReservationCardOrderStatus.SUPERSEDED) return false
        row.status = ReservationCardOrderStatus.NEEDS_REVIEW
        row.reviewReason = "still open 48 hours after the checkout was created — check the payment in the Mollie dashboard"
        log.warn("Mollie booking payment {} (ledger {}) still open after 48 h -> NEEDS_REVIEW", row.providerOrderId, row.id)
        return true
    }

    /** M4 bookkeeping: the post-commit booking confirmation of a completed row ended ([state] DONE or FAILED). */
    fun markPromote(
        id: Long,
        state: CardOrderPromoteState,
    ) {
        val row = orders.lockById(id) ?: return
        row.promoteState = state
    }

    /** Only the webhook stamp (Mollie said "changed" but nothing to apply). */
    fun stampWebhook(
        id: Long,
        event: String,
    ) {
        val row = orders.lockById(id) ?: return
        stamp(row, event)
    }

    private fun review(
        row: ReservationCardOrder,
        reason: String,
    ): CompletionResult {
        row.status = ReservationCardOrderStatus.NEEDS_REVIEW
        row.reviewReason = reason.take(MAX_REVIEW_REASON)
        log.warn("Mollie booking order {} (ledger {}) NEEDS_REVIEW: {}", row.providerOrderId, row.id, reason)
        return CompletionResult(completedNow = false, promote = false, reservationId = row.reservationId, alert = reason)
    }

    private fun stamp(
        row: ReservationCardOrder,
        event: String?,
    ) {
        val now = Instant.now()
        if (event != null) {
            row.lastWebhookEvent = event.take(MAX_EVENT_LENGTH)
            row.lastWebhookAt = now
        } else {
            row.lastCheckedAt = now
        }
    }

    companion object {
        /** Mollie metadata keys checked on completion (the payment is created before its ledger row). */
        const val FLOW_METADATA_KEY = "reservationFlowId"
        const val PHASES_METADATA_KEY = "phaseIds"
        private const val MAX_REVIEW_REASON = 1000
        private const val MAX_EVENT_LENGTH = 64

        /** A PENDING order is reused only while Mollie keeps it open for at least this long. */
        val REUSE_MARGIN: Duration = Duration.ofMinutes(10)

        /** `payment_phase_id` key of [ReservationCardOrderRepository.findReusable] (NULL = the pay-full path). */
        private const val NO_PAYMENT_PHASE = -1L

        fun reusableUntil(): Instant = Instant.now().plus(REUSE_MARGIN)

        fun paymentPhaseKey(paymentPhaseId: Long?): Long = paymentPhaseId ?: NO_PAYMENT_PHASE

        /** Advisory lock key serialising the checkouts of one reservation flow. */
        fun flowLockKey(reservationFlowId: Long): String = "reservation-card-order|$reservationFlowId"
    }
}
