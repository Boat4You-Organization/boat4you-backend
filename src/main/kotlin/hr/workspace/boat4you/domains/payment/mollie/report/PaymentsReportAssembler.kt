package hr.workspace.boat4you.domains.payment.mollie.report

import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceTransaction
import java.time.Instant

/**
 * Turns Mollie's balance transactions into the ledger part of the report (pure). Mollie pays the balance out in
 * transfers; everything booked between two transfers is what the later transfer consists of.
 */
internal object PaymentsReportAssembler {
    /** A transfer from the balance to the bank account. */
    val PAYOUT_TYPES = setOf("outgoing-transfer", "outgoing-custom-amount-transfer")

    /** Money going back to a payer, and the reversals of that. */
    val MONEY_BACK_TYPES = setOf("refund", "returned-refund", "chargeback", "chargeback-reversal")

    /**
     * [transactions] in any order, reaching back at least to the last transfer before [from]; the period is
     * `[from, to)`. [parties] = our ledgers' view of the payments, by Mollie payment id.
     */
    fun ledger(
        transactions: List<MollieBalanceTransaction>,
        from: Instant,
        to: Instant,
        parties: Map<String, ReportParty>,
        complete: Boolean,
    ): ReportLedger {
        val untilEnd = transactions.filter { it.createdAt != null && it.createdAt.isBefore(to) }.sortedBy { it.createdAt }
        val payouts = ArrayList<ReportPayout>()
        var open = ArrayList<ReportLedgerLine>()
        untilEnd.forEach { transaction ->
            if (transaction.type in PAYOUT_TYPES) {
                if (!transaction.createdAt!!.isBefore(from)) {
                    payouts +=
                        ReportPayout(
                            at = transaction.createdAt,
                            amountMinor = -transaction.netMinor(),
                            settlementId = transaction.settlementId,
                            transferId = transaction.transferId,
                            lines = open,
                        )
                }
                open = ArrayList()
            } else {
                open += transaction.toLine(parties)
            }
        }
        return ReportLedger(
            moneyBack = untilEnd.filter { it.type in MONEY_BACK_TYPES && !it.createdAt!!.isBefore(from) }.map { it.toLine(parties) },
            payouts = payouts,
            notPaidOut = open,
            complete = complete,
        )
    }

    private fun MollieBalanceTransaction.toLine(parties: Map<String, ReportParty>): ReportLedgerLine {
        val net = netMinor()
        return ReportLedgerLine(
            at = createdAt!!,
            type = type ?: "unknown",
            molliePaymentId = paymentId,
            description = paymentDescription,
            party = paymentId?.let { parties[it] },
            grossMinor = initialAmount?.toMinorOrNull() ?: net,
            deductionsMinor = deductions?.toMinorOrNull() ?: 0,
            netMinor = net,
        )
    }

    private fun MollieBalanceTransaction.netMinor(): Long = resultAmount?.toMinorOrNull() ?: 0
}
