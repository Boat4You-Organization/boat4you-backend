package hr.workspace.boat4you.domains.reservation.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Whole-euro split of the reservation-level bank-transfer fee (BANK_TRANSFER_FIXED_FEE,
 * e.g. 32 EUR) across the reservation's payment phases. Mario 1.7.2026: the fee is
 * mandatory per reservation, distributed per wire (2 phases -> 16 EUR each), and must
 * never introduce cents — so the split is integer euros with earlier phases absorbing
 * the remainder (32 across 3 phases -> 11 / 11 / 10; shares always sum to the fee).
 *
 * The frontend mirrors this math (bankFeeShareForPhase in boat4you-web) so the amount
 * shown at payment-method selection equals the amount the wire emails ask for.
 */
object BankTransferFeeShare {
    /** Share (whole EUR) carried by the phase at [phaseIndex] among [phaseCount] deadline-sorted phases. */
    fun shareFor(totalFee: BigDecimal, phaseCount: Int, phaseIndex: Int): BigDecimal {
        val fee = totalFee.setScale(0, RoundingMode.HALF_UP).toInt()
        if (fee <= 0) return BigDecimal.ZERO
        val n = if (phaseCount <= 0) 1 else phaseCount
        val idx = phaseIndex.coerceIn(0, n - 1)
        val base = fee / n
        val remainder = fee % n
        return BigDecimal(base + if (idx < remainder) 1 else 0)
    }
}
