package hr.workspace.boat4you.domains.payment.mollie.report

import java.time.Instant
import java.time.LocalDate

/*
 * The accounting report of the Mollie payments (raw values; PaymentsReportPdf formats them). Two sources: our own
 * ledgers (who paid, for which booking) and Mollie's balance transactions (what Mollie booked and paid out).
 */

enum class ReportPaymentKind {
    /** A payment of an online booking (`reservation_card_order`). */
    BOOKING,

    /** A payment through the secret `/pay` link (`custom_payment`). */
    LINK,
}

/** Who paid and for which booking, as far as our ledgers know. */
data class ReportParty(
    val kind: ReportPaymentKind,
    val payerName: String?,
    val payerEmail: String?,
    /** Booking number, or the reference the payer typed on the payment link. */
    val reference: String,
    /** Link payments: number of the reservation the payment is tied to, when it is not the typed reference itself. */
    val linkedReservation: String? = null,
    /**
     * Our ledger holds the payment as NEEDS_REVIEW (double payment, amount not matching, …): Mollie may have charged
     * it, but it is not among the period's payments until the office settles it in the admin.
     */
    val needsReview: Boolean = false,
)

/** One paid payment of the period (section "payments"). */
data class ReportPayment(
    val paidAt: Instant,
    val party: ReportParty,
    /** `CARD` or `KLARNA`. */
    val method: String,
    val molliePaymentId: String,
    /** Charged, cents. */
    val amountMinor: Long,
    /** Booking payments: the instalment without the card surcharge, and the surcharge (cents). */
    val baseMinor: Long? = null,
    val surchargeMinor: Long? = null,
    /** Mollie's fee, cents; null = Mollie has not booked the payment yet. */
    val feeMinor: Long? = null,
    /** Refunded + charged back so far, cents. */
    val moneyBackMinor: Long = 0,
)

/** One movement on the Mollie balance. Amounts as Mollie signs them: money leaving the balance is negative. */
data class ReportLedgerLine(
    val at: Instant,
    /** Mollie's transaction type (`payment`, `refund`, `chargeback`, …). */
    val type: String,
    val molliePaymentId: String?,
    /** Mollie's own description of the payment; shown when the payment is not in our ledgers. */
    val description: String?,
    val party: ReportParty?,
    /** Before Mollie's deductions, cents. */
    val grossMinor: Long,
    /** What Mollie deducted, cents, zero or negative. */
    val deductionsMinor: Long,
    /** What reached (or left) the balance, cents. */
    val netMinor: Long,
)

/** One transfer from the Mollie balance to the bank account, with the movements booked since the transfer before it. */
data class ReportPayout(
    val at: Instant,
    /** Transferred to the bank, cents (positive). */
    val amountMinor: Long,
    val settlementId: String?,
    val transferId: String?,
    val lines: List<ReportLedgerLine>,
) {
    val linesNetMinor: Long get() = lines.sumOf { it.netMinor }

    /** Transfer minus its lines: not zero when balance was carried over from before, or the transfer was partial. */
    val differenceMinor: Long get() = amountMinor - linesNetMinor
}

data class ReportLedger(
    /** Refunds and chargebacks (and their reversals) booked in the period. */
    val moneyBack: List<ReportLedgerLine>,
    /** The transfers to the bank made in the period, oldest first. */
    val payouts: List<ReportPayout>,
    /** Booked up to the end of the period and not part of any transfer yet. */
    val notPaidOut: List<ReportLedgerLine>,
    /**
     * False when the reading of the balance stopped early (page or time limit): the oldest payouts and refunds of the
     * period, or the oldest lines of the first payout, may be missing.
     */
    val complete: Boolean,
)

data class PaymentsReport(
    /** Both inclusive, Croatian calendar days. */
    val from: LocalDate,
    val to: LocalDate,
    val generatedAt: Instant,
    val payments: List<ReportPayment>,
    /** Null = Mollie's balance could not be read (no organisation token, or Mollie did not answer). */
    val ledger: ReportLedger?,
)
