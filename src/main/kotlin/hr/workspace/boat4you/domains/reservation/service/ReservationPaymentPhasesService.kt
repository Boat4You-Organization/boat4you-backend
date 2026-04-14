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

    fun calculatePaymentPhases(
        now: LocalDate = LocalDate.now(),
        reservationStartDate: LocalDate,
        totalPrice: Double,
    ): List<Pair<LocalDate, Double>> {
        // If reservation is made one month or less in advance
        if (betweenInclusive(reservationStartDate, now, now.plusMonths(1))) {
            return listOf(Pair(now, totalPrice).roundDecimals())
        }

        // If reservation is made from (month + 1) days up to a year in advance
        if (betweenInclusive(reservationStartDate, now.plusMonths(1).plusDays(1), now.plusYears(1))) {
            val halfPrice = (0.5 * totalPrice).roundDecimals()
            return listOf(
                Pair(now, halfPrice).roundDecimals(),
                Pair(reservationStartDate.minusMonths(1), totalPrice.roundDecimals() - halfPrice).roundDecimals(),
            )
        }

        // If reservation is made (year + 1) days or more in advance, until 15.2. next year
        if (betweenInclusive(reservationStartDate, now.plusYears(1).plusDays(1), LocalDate.of(now.year + 1, Month.FEBRUARY, 15))) {
            val halfPrice = (0.5 * totalPrice).roundDecimals()
            return listOf(
                Pair(now, halfPrice).roundDecimals(),
                Pair(reservationStartDate.minusMonths(1), totalPrice.roundDecimals() - halfPrice).roundDecimals(),
            )
        }

        // If reservation is made (year + 1) days or more in advance, and reservationStartDate is between 16.2. and 31.12.
        if (afterInclusive(reservationStartDate, now.plusYears(1).plusDays(1)) &&
            betweenInclusive(reservationStartDate, LocalDate.of(reservationStartDate.year, Month.FEBRUARY, 16), LocalDate.of(reservationStartDate.year, Month.DECEMBER, 31))
        ) {
            val quarterPrice = (0.25 * totalPrice).roundDecimals()
            val halfPrice = (totalPrice.roundDecimals() - 2 * quarterPrice).roundDecimals()
            return listOf(
                Pair(now, quarterPrice).roundDecimals(),
                Pair(LocalDate.of(reservationStartDate.year, Month.JANUARY, 15), quarterPrice).roundDecimals(),
                Pair(reservationStartDate.minusMonths(1), halfPrice).roundDecimals(),
            )
        }

        // If reservation is made (year + 1) days or more in advance, and reservationStartDate is between 1.1. and 15.2.
        if (afterInclusive(reservationStartDate, now.plusYears(1)) &&
            betweenInclusive(reservationStartDate, LocalDate.of(reservationStartDate.year, Month.JANUARY, 1), LocalDate.of(reservationStartDate.year, Month.FEBRUARY, 15))
        ) {
            val quarterPrice = (0.25 * totalPrice).roundDecimals()
            val halfPrice = (totalPrice.roundDecimals() - 2 * quarterPrice).roundDecimals()
            return listOf(
                Pair(now, quarterPrice).roundDecimals(),
                Pair(LocalDate.of(reservationStartDate.year - 1, Month.JANUARY, 15), quarterPrice).roundDecimals(),
                Pair(reservationStartDate.minusMonths(1), halfPrice).roundDecimals(),
            )
        }

        logger.error("Error calculating price phases for reservationStartDate $reservationStartDate and totalPrice $totalPrice")
        throw IllegalStateException()
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
            vivaTransactionId = this.vivaTransactionId,
        )
}
