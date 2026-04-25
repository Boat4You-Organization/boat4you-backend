package hr.workspace.boat4you.domains.reservation.service

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
