package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.reservation.dto.DailyCount
import hr.workspace.boat4you.domains.reservation.dto.DashboardMetricsDto
import hr.workspace.boat4you.domains.reservation.dto.MyReservationDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.MyReservationsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtraRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationViewRepository
import hr.workspace.boat4you.domains.reservation.mapper.ReservationMappers
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class ReservationFlowQueryingService(
    private val reservationViewRepository: ReservationViewRepository,
    private val reservationExtraRepository: ReservationExtraRepository,
    private val reservationMappers: ReservationMappers,
    private val yachtRepository: YachtRepository,
) {
    fun getMyReservations(
        userId: Long,
        currency: CurrencyEnum,
    ): List<MyReservationsDto> {
        val reservations = reservationViewRepository.findAllByReservationUserId(userId)
        return reservations.map { reservationMappers.toMyReservationsResponse(it, currency) }
    }

    fun getMyReservationDetails(
        id: Long,
        userId: Long,
        language: LanguageEnum,
        currency: CurrencyEnum,
    ): MyReservationDetailsDto {
        val reservation =
            reservationViewRepository.findByReservationIdAndReservationUserId(id, userId)
                ?: throw RuntimeException("Reservation for user not found")
        val selectedExtras = reservationExtraRepository.findAllByReservationFlowId(reservation.reservationFlowId!!)
        val yacht = yachtRepository.findById(reservation.yachtId!!).get()

        return reservationMappers.toMyReservationDetailsResponse(
            reservation,
            selectedExtras,
            yacht,
            language,
            currency,
        )
    }

    fun getAllForAdmin(
        status: ReservationStatus?,
        userId: Long?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        reservationId: Long?,
        search: String?,
        pageable: Pageable,
    ): Page<ReservationViewDto> {
        val startAt = dateFrom?.atStartOfDay()
        val endAt = dateTo?.atTime(23, 59, 59)
        return reservationViewRepository
            .findAllReservationsByParams(status, userId, startAt, endAt, reservationId, search, pageable)
            .map { reservationMappers.toShortDto(it) }
    }

    fun getReservationByIdForAdmin(
        id: Long,
        currency: CurrencyEnum,
        language: LanguageEnum,
    ): ReservationViewDetailsDto {
        val reservation =
            reservationViewRepository
                .findById(id)
                .orElseThrow { IllegalArgumentException("Reservation with id $id not found") }
        return buildDetailsDto(reservation, currency, language)
    }

    fun getReservationByNumberForAdmin(
        reservationNumber: String,
        currency: CurrencyEnum,
        language: LanguageEnum,
    ): ReservationViewDetailsDto {
        val reservation =
            reservationViewRepository.findByReservationNumber(reservationNumber)
                ?: throw IllegalArgumentException("Reservation with number $reservationNumber not found")
        return buildDetailsDto(reservation, currency, language)
    }

    private fun buildDetailsDto(
        reservation: hr.workspace.boat4you.domains.reservation.jpa.ReservationView,
        currency: CurrencyEnum,
        language: LanguageEnum,
    ): ReservationViewDetailsDto {
        val yacht = yachtRepository.findById(reservation.yachtId!!).get()
        val selectedExtras = reservationExtraRepository.findAllByReservationFlowId(reservation.reservationFlowId!!)
        return reservationMappers.toDetailsDto(reservation, yacht, selectedExtras, currency, language)
    }

    /**
     * Aggregates for the admin dashboard. Read-only and intentionally cheap:
     * a handful of `COUNT(*)` and one `SUM(...)` against `reservation_view`.
     * No filtering by agency or user — broker dashboard reflects the whole
     * tenant.
     */
    fun getDashboardMetrics(): DashboardMetricsDto {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        // ISO week starts Monday; align to local convention.
        val weekStart = today.with(DayOfWeek.MONDAY).atStartOfDay()
        val weekEnd = weekStart.plusWeeks(1)
        val monthStart = today.withDayOfMonth(1).atStartOfDay()
        val monthEnd = monthStart.plusMonths(1)
        val yearStart = today.withDayOfYear(1).atStartOfDay()
        val yearEnd = yearStart.plusYears(1)

        val bookingsThisWeek = reservationViewRepository.countByCreatedAtBetween(weekStart, weekEnd)
        val bookingsThisMonth = reservationViewRepository.countByCreatedAtBetween(monthStart, monthEnd)
        val confirmedReservations = reservationViewRepository.countBySysStatus(ReservationStatus.RESERVATION)
        val revenueYearToDate: BigDecimal =
            reservationViewRepository.sumCommissionByCreatedAtBetween(yearStart, yearEnd)

        // 7-day rolling window ending today (inclusive): index 0 = 6 days ago.
        // Repository returns only days that have rows; pad missing days with 0
        // so the FE renders one bar per index.
        val chartFrom = today.minusDays(6).atStartOfDay()
        val chartTo = today.plusDays(1).atStartOfDay()
        val grouped: Map<LocalDate, Long> =
            reservationViewRepository.countPerDayBetween(chartFrom, chartTo)
                .associate { row ->
                    val day = row[0] as LocalDate
                    val cnt = (row[1] as Number).toLong()
                    day to cnt
                }
        val weeklyChart =
            (0..6).map { offset ->
                val day = today.minusDays((6 - offset).toLong())
                DailyCount(day = day.toString(), count = grouped[day] ?: 0L)
            }

        return DashboardMetricsDto(
            bookingsThisWeek = bookingsThisWeek,
            bookingsThisMonth = bookingsThisMonth,
            confirmedReservations = confirmedReservations,
            revenueYearToDate = revenueYearToDate,
            weeklyChart = weeklyChart,
        )
    }
}
