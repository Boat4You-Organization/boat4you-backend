package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.reservation.dto.PaymentPhaseDto
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import kotlin.jvm.optionals.getOrElse

@Service
class ReservationPaymentPhasesService(
    private val reservationRepository: ReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val paymentPhasesRepository: ReservationPaymentPhaseRepository,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    /**
     * Payment schedule rules (business logic owned by the charter team):
     *
     *   A) Charter within 2 months → 100% now (no time for instalments).
     *   B) Charter later the SAME year (≥ 2 months out) → 50% now + 50% one
     *      month before charter.
     *   C) Charter in NEXT year (or later) → 25% now + 25% on January 15th of
     *      the charter year + 50% one month before charter.
     *
     *   If the Jan 15 instalment falls AFTER the "one month before charter"
     *   date (happens when the charter is early January/February of next year),
     *   we fall back to rule B (50% now + 50% one month before charter).
     */
    fun calculatePaymentPhases(
        now: LocalDate = LocalDate.now(),
        reservationStartDate: LocalDate,
        totalPrice: Double,
    ): List<Pair<LocalDate, Double>> {
        val monthBeforeCharter = reservationStartDate.minusMonths(1)

        // Rule A — charter within 2 months
        if (betweenInclusive(reservationStartDate, now, now.plusMonths(2))) {
            return listOf(Pair(now, totalPrice).roundDecimals())
        }

        val isNextYearOrLater = reservationStartDate.year > now.year

        // Rule C — charter in next year (or later)
        if (isNextYearOrLater) {
            val januaryDeadline = LocalDate.of(reservationStartDate.year, Month.JANUARY, 15)
            // The Jan-15 instalment must strictly precede the "month before" one.
            if (januaryDeadline.isAfter(monthBeforeCharter) || januaryDeadline.isBefore(now)) {
                // Fall back to 50/50 if the 3-phase schedule would collapse.
                return fiftyFiftySplit(now, monthBeforeCharter, totalPrice)
            }
            val quarterPrice = (0.25 * totalPrice).roundDecimals()
            val halfPrice = (totalPrice.roundDecimals() - 2 * quarterPrice).roundDecimals()
            return listOf(
                Pair(now, quarterPrice).roundDecimals(),
                Pair(januaryDeadline, quarterPrice).roundDecimals(),
                Pair(monthBeforeCharter, halfPrice).roundDecimals(),
            )
        }

        // Rule B — charter later this year
        return fiftyFiftySplit(now, monthBeforeCharter, totalPrice)
    }

    private fun fiftyFiftySplit(
        now: LocalDate,
        monthBeforeCharter: LocalDate,
        totalPrice: Double,
    ): List<Pair<LocalDate, Double>> {
        val halfPrice = (0.5 * totalPrice).roundDecimals()
        return listOf(
            Pair(now, halfPrice).roundDecimals(),
            Pair(monthBeforeCharter, totalPrice.roundDecimals() - halfPrice).roundDecimals(),
        )
    }

    fun calculatePaymentPhases(
        now: LocalDate = LocalDate.now(),
        reservationStartDate: LocalDate,
        totalPrice: BigDecimal,
    ): List<Pair<LocalDate, Double>> = calculatePaymentPhases(now, reservationStartDate, totalPrice.toDouble())

    /**
     * Partner-aware payment-phase calculation. When the partner (MMK / NauSys)
     * synced an explicit `offer.offerPaymentPlans` for this offer, that plan
     * is the source of truth for *timing and ratios* — every agency configures
     * its own schedule (1 rata, 2 rate, 3 rate, custom %), and we honour it so
     * partner reconciliation lines up.
     *
     * Our agency discount is applied **proportionally** to those ratios: if the
     * agency runs 20% / 80% and we give the customer a 5% discount, the
     * customer pays 20% × (price - 5%) and 80% × (price - 5%), not the partner-
     * side numbers. The split goes against `clientTotalPrice` (already discounted).
     *
     * Fallback to the internal A/B/C rules when:
     *   - `offer.offerPaymentPlans` is empty (custom yacht / sync gap / agency
     *     didn't configure a plan / last-minute partner emit)
     *   - all partner deadlines are in the past (stale data — sync hasn't
     *     refreshed since the charter window started)
     *   - partner total amount sums to ≤ 0 (defensive — bad data)
     *
     * Custom yachts never reach this path (they're inquiry-only and never enter
     * the booking flow), but the empty-plan branch covers them defensively.
     */
    fun calculatePaymentPhases(
        offer: Offer,
        clientTotalPrice: Double,
        now: LocalDate = LocalDate.now(),
    ): List<Pair<LocalDate, Double>> {
        val reservationStartDate = offer.dateFrom
            ?: return calculatePaymentPhases(now, LocalDate.now(), clientTotalPrice)

        // Filter out partner phases whose deadline has already passed —
        // happens with stale sync data or last-minute bookings where the
        // partner-supplied "20% pri rezervaciji" deadline is technically
        // in the past by the time the customer hits checkout.
        val livePartnerPhases = offer.offerPaymentPlans
            .filter { it.date != null && !it.date!!.isBefore(now) }
            .filter { it.amount != null && it.amount!! > BigDecimal.ZERO }
            .sortedBy { it.date }

        if (livePartnerPhases.isEmpty()) {
            logger.debug("No partner payment plan for offer ${offer.id} — using B4Y A/B/C rules")
            return calculatePaymentPhases(now, reservationStartDate, clientTotalPrice)
        }

        val partnerTotal = livePartnerPhases.sumOf { it.amount!!.toDouble() }
        if (partnerTotal <= 0.0) {
            logger.warn("Partner payment plan for offer ${offer.id} has non-positive total — falling back to B4Y rules")
            return calculatePaymentPhases(now, reservationStartDate, clientTotalPrice)
        }

        // Apply partner ratios to OUR discounted client_price.
        // Last bucket absorbs the rounding delta so the sum exactly matches
        // clientTotalPrice (mirrors the existing fiftyFiftySplit behaviour).
        val rounded = clientTotalPrice.roundDecimals()
        val phases = livePartnerPhases.mapIndexed { index, phase ->
            val ratio = phase.amount!!.toDouble() / partnerTotal
            val rawAmount = (ratio * rounded).roundDecimals()
            Pair(phase.date!!, rawAmount)
        }.toMutableList()

        // Adjust last bucket so phases sum to clientTotalPrice exactly
        val sumSoFar = phases.dropLast(1).sumOf { it.second }
        val last = phases.last()
        phases[phases.size - 1] = Pair(last.first, (rounded - sumSoFar).roundDecimals())

        return phases
    }

    @Transactional(readOnly = true)
    fun getPaymentPhases(reservationId: Long): List<PaymentPhaseDto> {
        val dbReservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val dbReservationFlowId = dbReservation.reservationFlow!!.id!!
        val reservationFlowIdsInChain = reservationFlowRepository.findIdsInReservationFlowChain(dbReservationFlowId)
        val allPaymentPhases =
            paymentPhasesRepository.findByReservationFlowIdIn(reservationFlowIdsInChain).filter { paymentPhase ->
                paymentPhase.reservationFlow.id == dbReservationFlowId || paymentPhase.paidOn != null
            }

        return allPaymentPhases
            .sortedBy { it.deadline }
            .map { it.toDto() }
    }

    @Transactional(readOnly = false)
    fun markPaymentPhasePaid(
        reservationId: Long,
        paymentPhaseIds: List<Long>,
    ): List<PaymentPhaseDto> {
        val dbReservation = reservationRepository.findById(reservationId).getOrElse { throw ReservationNotExistException() }
        val phases =
            dbReservation.reservationFlow
                ?.paymentPhases
                ?.sortedBy { it.deadline }
                ?.filter { it.id!! in paymentPhaseIds } ?: emptyList()

        phases.forEach { it.paidOn = Instant.now() }

        return phases.map { it.toDto() }
    }

    private fun Double.roundDecimals(): Double = this.toBigDecimal().setScale(2, RoundingMode.UP).toDouble()

    private fun Pair<LocalDate, Double>.roundDecimals(): Pair<LocalDate, Double> = Pair(this.first, this.second.roundDecimals())

    private fun betweenInclusive(
        queryDate: LocalDate,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Boolean {
        if (!fromDate.isEqual(toDate) && toDate.isBefore(fromDate)) {
            return false
        }

        if (queryDate.isEqual(fromDate) || queryDate.isEqual(toDate)) {
            return true
        }
        return queryDate.isAfter(fromDate) && queryDate.isBefore(toDate)
    }

    private fun afterInclusive(
        queryDate: LocalDate,
        fromDate: LocalDate,
    ): Boolean {
        if (queryDate.isEqual(fromDate)) {
            return true
        }
        return queryDate.isAfter(fromDate)
    }

    private fun ReservationPaymentPhase.toDto(): PaymentPhaseDto =
        PaymentPhaseDto(
            id = this.id,
            deadline = this.deadline,
            amount = this.amount,
            paidOn = this.paidOn?.let { LocalDateTime.ofInstant(it, ZoneId.of("UTC")) },
            stripePaymentIntentId = this.stripePaymentIntentId,
        )
}
