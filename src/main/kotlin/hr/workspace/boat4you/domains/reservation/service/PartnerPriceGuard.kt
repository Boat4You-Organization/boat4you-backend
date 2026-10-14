package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.PartnerPriceGuardMode
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodKey
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.PartnerPriceChangedException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * WS-C2 (`PARTNER_PRICE_GUARD`): compares the price the partner put on the option it just created with the offer
 * snapshot the customer was priced from. Runs in the booking orchestration between partner createOption and
 * createReservation, i.e. before any Reservation exists.
 *
 * - MMK: `partnerNet = basePrice x (1 - discount / 100)` (MMK `discount` is a percentage, see
 *   [hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.Companion.fromMmkOfferObligatory]) against
 *   `offer.extTotalPrice` (the MMK offer `price`, after discount). Cross-check, logged only:
 *   `clientPrice - sum(items billed with the booking, payableInBase = false)`.
 * - NauSys: `wrapper.clientPrice` against `offer.extClientPrice`. Cross-check, logged only: `basePrice - discount`.
 * - Only EUR is compared (the offer sync requests EUR; the offer row has no currency).
 *
 * Above `snapshot + max(1 %, 10 EUR)`: ALERT logs and the booking proceeds; ENFORCE throws
 * [PartnerPriceChangedException] (409 / 3007), and the caller's existing compensation releases the partner option by
 * wrapper, abandons the flow (offer back to FREE) and alerts the admins. Always alert-only: a synthetic wrapper
 * (skipExternalSystem / global kill switch, no partner price at all), an admin override, a partner that auto-confirmed
 * straight to RESERVATION (a confirmed partner booking is never released from here) and a non-EUR price.
 * Lower or within tolerance: the booking proceeds at the price the customer saw.
 *
 * Confirmed-price rule: this class reads only. It never writes `calculatedTotalPrice`, the offer or any Reservation
 * field, and it never changes the outcome of a booking except by the one ENFORCE exception. Any failure of the check
 * itself is logged and the booking proceeds as today.
 */
@Component
class PartnerPriceGuard(
    private val realPriceSwitches: RealPriceSwitches,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val partnerPeriodCheckStore: PartnerPeriodCheckStore,
    transactionManager: PlatformTransactionManager,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // Short read-only transaction for the flow -> offer / yacht -> agency chain; nothing is written through it.
    private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /** What the guard knows about the booking, read from the flow. */
    data class BookingFacts(
        val reservationFlowId: Long,
        val offerId: Long?,
        val yachtId: Long?,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
        val system: ExternalSystemEnum?,
        val skipExternalSystem: Boolean,
        val extTotalPrice: BigDecimal?,
        val extClientPrice: BigDecimal?,
    ) {
        /** The exact period of the offer, when it is fully known. */
        val period: PeriodKey?
            get() = yachtId?.let { y -> dateFrom?.let { f -> dateTo?.let { t -> PeriodKey(y, f, t) } } }
    }

    enum class Outcome { NOT_COMPARED, LOWER, WITHIN, ABOVE }

    data class Verdict(
        val outcome: Outcome,
        val partnerNet: BigDecimal? = null,
        val snapshotNet: BigDecimal? = null,
        val tolerance: BigDecimal? = null,
        val crossCheckNet: BigDecimal? = null,
        /** Why an ABOVE (or an uncompared price) can never block the booking; null = enforceable. */
        val alertOnlyReason: String? = null,
        val note: String? = null,
    ) {
        /** ABOVE the tolerance and none of the alert-only cases: ENFORCE blocks the booking. */
        val enforceable: Boolean
            get() = outcome == Outcome.ABOVE && alertOnlyReason == null

        /** The cross-check formula disagrees with the primary one by more than the tolerance (calibration signal). */
        val crossCheckMismatch: Boolean
            get() =
                partnerNet != null && crossCheckNet != null && tolerance != null &&
                    (crossCheckNet - partnerNet).abs() > tolerance
    }

    /**
     * Runs the check for [reservationFlowId] against the createOption [wrapper]. Returns null when the switch is OFF
     * (no read at all) or the check could not run, otherwise the verdict it logged. Throws
     * [PartnerPriceChangedException] only in ENFORCE, only for an enforceable ABOVE.
     */
    fun check(
        reservationFlowId: Long,
        wrapper: ReservationResponseWrapper,
        adminOverride: Boolean = false,
    ): Verdict? {
        val mode = runCatching { realPriceSwitches.partnerPriceGuard }.getOrDefault(PartnerPriceGuardMode.OFF)
        val checked = if (mode == PartnerPriceGuardMode.OFF) null else verdictFor(reservationFlowId, wrapper, adminOverride)
        if (checked != null) {
            val (facts, verdict) = checked
            report(mode, facts, wrapper, verdict)
            if (mode == PartnerPriceGuardMode.ENFORCE && verdict.enforceable) {
                queueRecheck(facts)
                throw PartnerPriceChangedException(
                    reservationFlowId = reservationFlowId,
                    offerId = facts.offerId,
                    partnerNet = verdict.partnerNet!!,
                    snapshotNet = verdict.snapshotNet!!,
                    currency = wrapper.currency,
                )
            }
        }
        return checked?.second
    }

    /** Facts and verdict, or null (logged) when the flow is gone or anything in the check itself fails. */
    private fun verdictFor(
        reservationFlowId: Long,
        wrapper: ReservationResponseWrapper,
        adminOverride: Boolean,
    ): Pair<BookingFacts, Verdict>? =
        runCatching {
            val facts = readTx.execute { loadFacts(reservationFlowId) }
            if (facts == null) log.warn("PARTNER_PRICE_GUARD flow={} not checked: flow not found", reservationFlowId)
            facts?.let { it to evaluate(it, wrapper, adminOverride) }
        }.onFailure { log.warn("PARTNER_PRICE_GUARD flow={} not checked: the check failed", reservationFlowId, it) }
            .getOrNull()

    private fun loadFacts(reservationFlowId: Long): BookingFacts? {
        val flow = reservationFlowRepository.findById(reservationFlowId).orElse(null) ?: return null
        val offer = flow.offer
        val yacht = flow.yacht ?: offer?.yacht
        val agency = yacht?.agency
        return BookingFacts(
            reservationFlowId = reservationFlowId,
            offerId = offer?.id,
            yachtId = yacht?.id,
            dateFrom = offer?.dateFrom,
            dateTo = offer?.dateTo,
            system =
                ExternalSystemEnum
                    .fromValue(agency?.primarySource?.externalSystem?.id)
                    .takeIf { it != ExternalSystemEnum.UNKNOWN },
            skipExternalSystem = agency?.skipExternalSystem == true,
            extTotalPrice = offer?.extTotalPrice,
            extClientPrice = offer?.extClientPrice,
        )
    }

    private fun report(
        mode: PartnerPriceGuardMode,
        facts: BookingFacts,
        wrapper: ReservationResponseWrapper,
        verdict: Verdict,
    ) {
        val line =
            "PARTNER_PRICE_GUARD mode={} outcome={} flow={} offer={} yacht={} system={} partnerStatus={} " +
                "partnerNet={} snapshot={} tolerance={} crossCheck={} currency={} alertOnly={} note={}"
        val args =
            arrayOf<Any?>(
                mode, verdict.outcome, facts.reservationFlowId, facts.offerId, facts.yachtId, facts.system,
                wrapper.calculatedSysStatus, verdict.partnerNet, verdict.snapshotNet, verdict.tolerance,
                verdict.crossCheckNet, wrapper.currency, verdict.alertOnlyReason, verdict.note,
            )
        val loud =
            verdict.outcome == Outcome.ABOVE ||
                (verdict.outcome == Outcome.NOT_COMPARED && verdict.alertOnlyReason == ALERT_ONLY_CURRENCY)
        if (loud) log.warn(line, *args) else log.info(line, *args)
        if (verdict.crossCheckMismatch) {
            log.warn(
                "PARTNER_PRICE_GUARD calibration flow={} system={} partnerNet={} crossCheck={} differ by more than {}",
                facts.reservationFlowId,
                facts.system,
                verdict.partnerNet,
                verdict.crossCheckNet,
                verdict.tolerance,
            )
        }
    }

    /** The partner just quoted this exact period at another price: let the reverifiers ask it again soon. */
    private fun queueRecheck(facts: BookingFacts) {
        val system = facts.system
        val period = facts.period
        if (system != null && period != null) {
            runCatching {
                partnerPeriodCheckStore.upsertPending(
                    system,
                    period.yachtId,
                    period.dateFrom,
                    period.dateTo,
                    PeriodCheckPriority.ON_DEMAND_OR_BOOKING,
                )
            }.onFailure { log.warn("PARTNER_PRICE_GUARD flow={} re-check not queued", facts.reservationFlowId, it) }
        }
    }

    companion object {
        /** Tolerance: the larger of 1 % of the snapshot and 10 EUR. */
        val TOLERANCE_SHARE: BigDecimal = BigDecimal("0.01")
        val TOLERANCE_MIN_EUR: BigDecimal = BigDecimal("10")
        const val EUR = "EUR"
        const val ALERT_ONLY_SYNTHETIC = "synthetic wrapper (skipExternalSystem / kill switch)"
        const val ALERT_ONLY_CURRENCY = "non-EUR partner currency"
        const val ALERT_ONLY_ADMIN = "admin override"
        const val ALERT_ONLY_CONFIRMED = "partner auto-confirmed RESERVATION"
        private val HUNDRED = BigDecimal("100")
        private const val MONEY_SCALE = 2

        /** Pure comparison, no I/O. */
        fun evaluate(
            facts: BookingFacts,
            wrapper: ReservationResponseWrapper,
            adminOverride: Boolean = false,
        ): Verdict = notComparable(facts, wrapper) ?: compare(facts, wrapper, adminOverride)

        private fun notComparable(
            facts: BookingFacts,
            wrapper: ReservationResponseWrapper,
        ): Verdict? {
            val synthetic = facts.skipExternalSystem || wrapper.externalId == null || wrapper.externalStatus == SKIP_OPTION_STATUS
            val discount = wrapper.discount
            return when {
                synthetic -> Verdict(Outcome.NOT_COMPARED, alertOnlyReason = ALERT_ONLY_SYNTHETIC, note = "no partner price")
                !wrapper.currency.trim().equals(EUR, ignoreCase = true) ->
                    Verdict(Outcome.NOT_COMPARED, alertOnlyReason = ALERT_ONLY_CURRENCY)
                facts.system != ExternalSystemEnum.MMK && facts.system != ExternalSystemEnum.NAUSYS ->
                    Verdict(Outcome.NOT_COMPARED, note = "unknown partner system")
                facts.system == ExternalSystemEnum.MMK && (discount.signum() < 0 || discount > HUNDRED) ->
                    Verdict(Outcome.NOT_COMPARED, note = "MMK discount $discount is not a percentage")
                else -> null
            }
        }

        /** (partnerNet, snapshot, crossCheck) for an MMK or NauSys wrapper in EUR. */
        private fun figures(
            facts: BookingFacts,
            wrapper: ReservationResponseWrapper,
        ): Triple<BigDecimal, BigDecimal?, BigDecimal> =
            if (facts.system == ExternalSystemEnum.MMK) {
                val net = wrapper.basePrice * (BigDecimal.ONE - wrapper.discount.divide(HUNDRED))
                val withBooking =
                    wrapper.extras
                        .orEmpty()
                        .filter { it.payableInBase != true }
                        .mapNotNull { it.price }
                        .fold(BigDecimal.ZERO, BigDecimal::add)
                Triple(net, facts.extTotalPrice, wrapper.clientPrice - withBooking)
            } else {
                Triple(wrapper.clientPrice, facts.extClientPrice, wrapper.basePrice - wrapper.discount)
            }

        private fun compare(
            facts: BookingFacts,
            wrapper: ReservationResponseWrapper,
            adminOverride: Boolean,
        ): Verdict {
            val (net, snapshot, crossCheck) = figures(facts, wrapper)
            val partner = net.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            val cross = crossCheck.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            if (snapshot == null || snapshot.signum() <= 0) {
                return Verdict(Outcome.NOT_COMPARED, partnerNet = partner, crossCheckNet = cross, note = "no offer snapshot")
            }
            val tolerance = (snapshot * TOLERANCE_SHARE).max(TOLERANCE_MIN_EUR).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            val diff = partner - snapshot
            val outcome =
                when {
                    diff > tolerance -> Outcome.ABOVE
                    diff.signum() < 0 -> Outcome.LOWER
                    else -> Outcome.WITHIN
                }
            val alertOnly =
                when {
                    outcome != Outcome.ABOVE -> null
                    adminOverride -> ALERT_ONLY_ADMIN
                    wrapper.calculatedSysStatus == ReservationStatus.RESERVATION -> ALERT_ONLY_CONFIRMED
                    else -> null
                }
            return Verdict(
                outcome = outcome,
                partnerNet = partner,
                snapshotNet = snapshot,
                tolerance = tolerance,
                crossCheckNet = cross,
                alertOnlyReason = alertOnly,
            )
        }

        /** `ReservationIntegrationService.buildSkipOptionResponse` marks its synthetic wrapper with this status. */
        private const val SKIP_OPTION_STATUS = "SKIP-OPTION"
    }
}
