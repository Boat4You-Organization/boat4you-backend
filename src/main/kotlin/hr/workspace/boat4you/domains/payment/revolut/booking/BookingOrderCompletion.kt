package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * ===== STAGE C HOOK (Revolut plan 2A.6, 2A.10 M2/M4) =====
 *
 * Records a booking order that Revolut reports as `completed` ([order], already read from Revolut, `state=completed`)
 * for the ledger row [ledgerId]. The stage C implementation is `ReservationCardOrderTx.applyCompleted` plus the
 * promote-after-commit of the pay-full path. Contract:
 * - called OUTSIDE any transaction — by [RevolutBookingPaymentService] (a stale PENDING order found while creating a
 *   new checkout, and the status endpoint) and by the stage C webhook handler / reconcile job;
 * - runs `applyCompleted` in its OWN transaction (never `REQUIRES_NEW` inside the create transaction — M2): lock the
 *   ledger row, verdict, lock + mark the phases, ledger COMPLETED or NEEDS_REVIEW, `ReservationPaymentRecordedEvent`;
 *   idempotent (an already COMPLETED / NEEDS_REVIEW row is a no-op);
 * - a verdict (NEEDS_REVIEW) is a result, not an exception.
 * The checkout answers 409 CARD_ALREADY_PAID only after this call has returned.
 */
fun interface BookingOrderCompletion {
    fun applyCompleted(
        ledgerId: Long,
        order: RevolutOrder,
    )
}

/**
 * STAGE B PLACEHOLDER — stage C deletes this class and provides the real [BookingOrderCompletion] (two beans would fail
 * the boot, on purpose). It records NOTHING and logs an ERROR, so a completion reaching it is visible. Harmless while
 * `application.payment.card-provider` stays `stripe` (the default) until stage C is merged.
 */
@Component
class NotYetWiredBookingOrderCompletion : BookingOrderCompletion {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun applyCompleted(
        ledgerId: Long,
        order: RevolutOrder,
    ) {
        log.error(
            "Revolut booking order {} (ledger {}) is completed at Revolut but the completion handler is not wired yet (stage C) - NOT recorded",
            order.id,
            ledgerId,
        )
    }
}
