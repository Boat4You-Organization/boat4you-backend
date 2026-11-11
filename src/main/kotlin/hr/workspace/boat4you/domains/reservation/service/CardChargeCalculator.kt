package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What an online-booking card payment charges: which payment phases, the base amount, the card surcharge and the
 * amount in cents. Pure (no I/O, no Spring): phase selection + surcharge + `toCentsLong`.
 * `CardChargeCalculatorTests` pins the golden values; do not change the arithmetic without changing those.
 */
object CardChargeCalculator {
    /** Phases a checkout pays and their summed amount (before the card surcharge). */
    data class Selection(
        val baseAmount: BigDecimal,
        /** Empty only for the legacy "pay full, no phase rows yet" fallback ([baseAmount] = calculated total). */
        val phases: List<ReservationPaymentPhase>,
    )

    data class CardCharge(
        val baseAmount: BigDecimal,
        val surchargeAmount: BigDecimal,
        val phases: List<ReservationPaymentPhase>,
    ) {
        val totalAmount: BigDecimal get() = baseAmount + surchargeAmount

        /** The amount sent to the card provider, in cents. */
        val totalMinor: Long get() = toCentsLong(totalAmount)
    }

    fun calculate(
        reservationFlow: ReservationFlow,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
        surchargePercentage: BigDecimal,
    ): CardCharge {
        val selection = select(reservationFlow, payFullAmount, paymentPhaseId)
        return CardCharge(selection.baseAmount, surcharge(selection.baseAmount, surchargePercentage), selection.phases)
    }

    fun select(
        reservationFlow: ReservationFlow,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
    ): Selection = select(reservationFlow.paymentPhases, reservationFlow.calculatedTotalPrice, payFullAmount, paymentPhaseId)

    /**
     * Phase selection (400 = [ParameterValidationException] for every invalid combination):
     * - `paymentPhaseId` wins over `payFullAmount`; an unknown or already paid phase is a 400;
     * - `payFullAmount=true`: every unpaid phase; when they sum to zero (no phase rows yet) the reservation's
     *   `calculatedTotalPrice` with no phases (400 when that is missing too);
     * - `payFullAmount=false`: the unpaid phase with the earliest deadline (400 when none remains);
     * - neither parameter: 400.
     */
    fun select(
        paymentPhases: Collection<ReservationPaymentPhase>,
        calculatedTotalPrice: BigDecimal?,
        payFullAmount: Boolean?,
        paymentPhaseId: Long?,
    ): Selection {
        if (payFullAmount == null && paymentPhaseId == null) {
            throw ParameterValidationException(mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter"))
        }

        // Phases that are NOT yet paid — the only valid targets for a fresh card charge. Admin-created replacement
        // reservations may have the first installment already marked paid (carry-over from a cancelled booking), and
        // an installment that was paid earlier on this same reservation obviously must not be re-charged.
        val unpaidPhases = paymentPhases.filter { it.paidOn == null }

        return when {
            // A specific phase was pinned from the UI (next-due or later installment). Wins over `payFullAmount` if
            // both are present. F3-026: reject already-paid phases before any provider call.
            paymentPhaseId != null -> {
                val phase =
                    paymentPhases.find { it.id == paymentPhaseId }
                        ?: throw ParameterValidationException(mapOf("paymentPhaseId" to "Payment phase not found"))
                if (phase.paidOn != null) {
                    throw ParameterValidationException(mapOf("paymentPhaseId" to "Payment phase is already paid"))
                }
                Selection(phase.amount, listOf(phase))
            }
            // "Pay full remaining" — sum of unpaid phases (NOT the full reservation total, otherwise customers with a
            // partially-paid reservation would be overcharged).
            payFullAmount == true -> {
                val remaining = unpaidPhases.sumOf { it.amount }
                if (remaining > BigDecimal.ZERO) {
                    Selection(remaining, unpaidPhases)
                } else {
                    // Legacy fallback: no phase rows yet (brand-new reservation, phases generated later). Use the
                    // reservation total. F1-026: explicit exception when even calculatedTotalPrice is missing.
                    val total =
                        calculatedTotalPrice
                            ?: throw ParameterValidationException(
                                mapOf("calculatedTotalPrice" to "Reservation has no total price and no payment phases"),
                            )
                    Selection(total, emptyList())
                }
            }
            // "Pay first installment" — next DUE unpaid phase, not the historically-oldest one which might already be
            // paid.
            payFullAmount == false -> {
                val phase =
                    unpaidPhases.minByOrNull { it.deadline }
                        ?: throw ParameterValidationException(mapOf("paymentPhaseId" to "No unpaid payment phases remain"))
                Selection(phase.amount, listOf(phase))
            }
            else -> throw ParameterValidationException(mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter"))
        }
    }

    /** The `CARD_PAYMENT_SURCHARGE` setting value (a percentage like "5" or "2.5"); missing = no surcharge. */
    fun surchargePercentage(settingValue: String?): BigDecimal = settingValue?.toBigDecimal() ?: BigDecimal(0.0)

    /**
     * Whole-euro surcharge (Mario 1.7.2026: fees must not introduce cents) — HALF_UP on the fee itself; the frontend
     * mirrors this with Math.round so the displayed fee always equals the charged fee.
     *
     * MUST multiply first, divide LAST with an explicit scale+rounding: the old `cardSurchargePercentage.div(100)`
     * used Kotlin's BigDecimal.div operator whose result scale = max(operand scales) = 0 for an integer percent like
     * "5", so 5 / 100 rounded to scale 0 = 0 → the surcharge was SILENTLY 0 on every card payment (Mario 12.7.2026:
     * the card was charged 2677.60 instead of 2811.60 on reservation 1441002).
     */
    fun surcharge(
        baseAmount: BigDecimal,
        surchargePercentage: BigDecimal,
    ): BigDecimal =
        baseAmount
            .multiply(surchargePercentage)
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)

    /** B6: HALF_UP so the cents amount rounds to nearest (the surcharge path can produce >2dp); UP always over-charged. */
    fun toCentsLong(amount: BigDecimal): Long = amount.setScale(2, RoundingMode.HALF_UP).times(100.toBigDecimal()).toLong()
}
