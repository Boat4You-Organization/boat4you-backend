package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Input of [CustomPaymentTx.insertPending]: an already validated and normalised attempt. */
data class CustomPaymentDraft(
    val site: String,
    val reference: String,
    val amountMinor: Long,
    val payerEmail: String,
    val clientIp: String?,
    val locale: String,
    val termsVersion: String,
    val acceptedAt: Instant,
    val reservationId: Long?,
    /** `CARD` or `KLARNA` (reservation_card_order uses the same two values). */
    val method: String = "CARD",
)

/** [payment] is the new row, or the still usable [existing] attempt with the same data (dedupe). */
data class PendingInsert(
    val payment: CustomPayment,
    val existing: Boolean,
)

/**
 * Every write of `custom_payment`, each in its own short transaction (DB only — Mollie is called by
 * CustomPaymentService between these calls, never inside one). [applyVerdict] / [applyRefund] take the row lock
 * first, so the webhook, the status endpoint, the reconcile job and admin Refresh can race freely: the second one
 * sees the first one's result and the receipt goes out once.
 */
@Service
@Transactional
class CustomPaymentTx(
    private val repository: CustomPaymentRepository,
    private val mailer: CustomPaymentMailer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Under an advisory lock on (site, reference, amount, e-mail): returns the PENDING attempt with the same data that
     * already has a checkout (created after [dedupeSince]) or inserts a new PENDING row.
     */
    fun insertPending(
        draft: CustomPaymentDraft,
        dedupeSince: Instant,
    ): PendingInsert {
        repository.lockDedupeKey(dedupeKey(draft))
        repository
            .findDedupeCandidates(
                draft.site,
                draft.reference,
                draft.amountMinor,
                draft.payerEmail,
                dedupeSince,
                CustomPaymentStatus.PENDING,
                draft.method,
            ).firstOrNull()
            ?.let { return PendingInsert(it, existing = true) }
        val row =
            CustomPayment().apply {
                method = draft.method
                site = draft.site
                reference = draft.reference
                amountMinor = draft.amountMinor
                payerEmail = draft.payerEmail
                clientIp = draft.clientIp
                locale = draft.locale
                termsVersion = draft.termsVersion
                acceptedAt = draft.acceptedAt
                reservationId = draft.reservationId
            }
        return PendingInsert(repository.saveAndFlush(row), existing = false)
    }

    /** The Mollie order exists: remember its id and hosted checkout URL. */
    fun attachOrder(
        id: Long,
        providerOrderId: String,
        checkoutUrl: String?,
    ) {
        val row = repository.lockById(id) ?: return
        row.providerOrderId = providerOrderId
        row.checkoutUrl = checkoutUrl
    }

    /** Mollie did not give us a usable order: the attempt ends as FAILED (the client sees an error and may retry). */
    fun markCreateFailed(
        id: Long,
        providerOrderId: String? = null,
    ) {
        val row = repository.lockById(id) ?: return
        if (providerOrderId != null && row.providerOrderId == null) row.providerOrderId = providerOrderId
        if (row.status == CustomPaymentStatus.PENDING) row.status = CustomPaymentStatus.FAILED
    }

    /**
     * Applies what Mollie says about the order. Rules:
     * - COMPLETE and not COMPLETED yet -> COMPLETED + completedAt + payment id + snapshot, receipt + office e-mail;
     *   when the row was not PENDING any more (FAILED / CANCELLED / NEEDS_REVIEW) also a "late completion" alert.
     * - NEEDS_REVIEW -> status + alert, never a receipt; a COMPLETED row is not downgraded (logged only) and a row
     *   already in NEEDS_REVIEW is not alerted again.
     * - FAILED / CANCELLED only from PENDING.
     * - still pending -> nothing.
     * [event] = the webhook event that triggered the check (stamped on the row); null = status endpoint / job / admin
     * (stamps `last_checked_at`). Returns the status after the call.
     */
    fun applyVerdict(
        id: Long,
        verdict: CustomPaymentVerdict,
        snapshotJson: String?,
        event: String? = null,
    ): CustomPaymentStatus? {
        val row = repository.lockById(id) ?: return null
        val now = Instant.now()
        stamp(row, event, now)
        when (verdict) {
            is CustomPaymentVerdict.Complete -> complete(row, verdict, snapshotJson, now)
            is CustomPaymentVerdict.NeedsReview -> flagForReview(row, verdict, snapshotJson)
            CustomPaymentVerdict.Failed -> closeIfPending(row, CustomPaymentStatus.FAILED, snapshotJson)
            CustomPaymentVerdict.Cancelled -> closeIfPending(row, CustomPaymentStatus.CANCELLED, snapshotJson)
            CustomPaymentVerdict.StillPending -> if (row.status == CustomPaymentStatus.PENDING) row.orderSnapshot = snapshotJson ?: row.orderSnapshot
        }
        return row.status
    }

    /** Only the webhook stamp (a declined / failed card attempt: the payer retries on the same hosted page). */
    fun stampWebhook(
        id: Long,
        event: String,
    ) {
        val row = repository.lockById(id) ?: return
        stamp(row, event, Instant.now())
    }

    /**
     * Refunds are monotonic: [totalRefundedMinor] (Mollie's `amountRefunded`, clamped to the amount) only ever raises
     * `refunded_amount_minor`; a repeated or older figure changes nothing and alerts nobody.
     */
    fun applyRefund(
        id: Long,
        totalRefundedMinor: Long,
    ): Long? {
        val row = repository.lockById(id) ?: return null
        val clamped = totalRefundedMinor.coerceIn(0L, row.amountMinor)
        if (clamped <= row.refundedAmountMinor) return row.refundedAmountMinor
        row.refundedAmountMinor = clamped
        val note = if (totalRefundedMinor > row.amountMinor) " (Mollie reported $totalRefundedMinor)" else ""
        log.info("Custom payment {} refunded {} of {} cents", row.id, clamped, row.amountMinor)
        mail("refund alert", row) {
            mailer.alert(row, CustomPaymentAlertKind.REFUND, "Refunded $clamped of ${row.amountMinor} cents$note")
        }
        return clamped
    }

    /** Chargebacks are monotonic like refunds: a higher Mollie total is stored and alerted once. */
    fun applyChargeback(
        id: Long,
        totalChargedBackMinor: Long,
    ): Long? {
        val row = repository.lockById(id) ?: return null
        val clamped = totalChargedBackMinor.coerceIn(0L, row.amountMinor)
        if (clamped <= row.chargedBackAmountMinor) return row.chargedBackAmountMinor
        row.chargedBackAmountMinor = clamped
        log.warn("Custom payment {} CHARGEBACK {} of {} cents", row.id, clamped, row.amountMinor)
        mail("chargeback alert", row) {
            mailer.alert(row, CustomPaymentAlertKind.CHARGEBACK, "Charged back $clamped of ${row.amountMinor} cents")
        }
        return clamped
    }

    /** Reconcile job: still PENDING 48 h after creation -> NEEDS_REVIEW + alert. */
    fun markStale(id: Long): Boolean {
        val row = repository.lockById(id) ?: return false
        if (row.status != CustomPaymentStatus.PENDING) return false
        row.status = CustomPaymentStatus.NEEDS_REVIEW
        log.warn("Custom payment {} still PENDING after 48 h -> NEEDS_REVIEW", row.id)
        mail("stale alert", row) { mailer.alert(row, CustomPaymentAlertKind.STALE, "Still pending 48 hours after it was created") }
        return true
    }

    /** Reconcile job: rows whose order was never attached -> FAILED after 15 min. */
    fun failUnattached(): Int = repository.failUnattached()

    /** Admin note (null clears it); the caller has validated the length. */
    fun updateNote(
        id: Long,
        note: String?,
    ): CustomPayment? {
        val row = repository.lockById(id) ?: return null
        row.adminNote = note
        return row
    }

    private fun complete(
        row: CustomPayment,
        verdict: CustomPaymentVerdict.Complete,
        snapshotJson: String?,
        now: Instant,
    ) {
        val previous = row.status
        if (previous == CustomPaymentStatus.COMPLETED) return
        row.status = CustomPaymentStatus.COMPLETED
        row.completedAt = now
        row.providerPaymentId = verdict.paymentId ?: row.providerPaymentId
        row.orderSnapshot = snapshotJson ?: row.orderSnapshot
        log.info("Custom payment {} COMPLETED (was {})", row.id, previous)
        mail("receipt", row) { mailer.receipt(row) }
        mail("office notification", row) { mailer.adminNotification(row) }
        if (previous != CustomPaymentStatus.PENDING) {
            mail("late completion alert", row) {
                mailer.alert(row, CustomPaymentAlertKind.LATE_COMPLETION, "Paid after the attempt was $previous")
            }
        }
    }

    private fun flagForReview(
        row: CustomPayment,
        verdict: CustomPaymentVerdict.NeedsReview,
        snapshotJson: String?,
    ) {
        when (val previous = row.status) {
            CustomPaymentStatus.NEEDS_REVIEW -> Unit
            CustomPaymentStatus.COMPLETED -> log.warn("Custom payment {} is COMPLETED but Mollie now says: {}", row.id, verdict.reason)
            else -> {
                row.status = CustomPaymentStatus.NEEDS_REVIEW
                row.orderSnapshot = snapshotJson ?: row.orderSnapshot
                log.warn("Custom payment {} NEEDS_REVIEW (was {}): {}", row.id, previous, verdict.reason)
                mail("review alert", row) { mailer.alert(row, CustomPaymentAlertKind.NEEDS_REVIEW, verdict.reason) }
            }
        }
    }

    private fun closeIfPending(
        row: CustomPayment,
        target: CustomPaymentStatus,
        snapshotJson: String?,
    ) {
        if (row.status != CustomPaymentStatus.PENDING) return
        row.status = target
        row.orderSnapshot = snapshotJson ?: row.orderSnapshot
        log.info("Custom payment {} {}", row.id, target)
    }

    private fun stamp(
        row: CustomPayment,
        event: String?,
        now: Instant,
    ) {
        if (event != null) {
            row.lastWebhookEvent = event.take(MAX_EVENT_LENGTH)
            row.lastWebhookAt = now
        } else {
            row.lastCheckedAt = now
        }
    }

    private fun mail(
        what: String,
        row: CustomPayment,
        send: () -> Unit,
    ) {
        runCatching(send).onFailure { log.error("Custom payment {}: {} could not be queued", row.id, what, it) }
    }

    private companion object {
        const val MAX_EVENT_LENGTH = 64

        fun dedupeKey(draft: CustomPaymentDraft): String = "custom-payment|${draft.site}|${draft.reference}|${draft.amountMinor}|${draft.payerEmail.lowercase()}"
    }
}
