package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface ReservationViewRepository :
    JpaRepository<ReservationView, Long>,
    JpaSpecificationExecutor<ReservationView> {
    fun findAllByReservationUserId(userId: Long): List<ReservationView>

    fun findByReservationIdAndReservationUserId(
        reservationId: Long,
        userId: Long,
    ): ReservationView?

    @Query(
        """
        SELECT rv FROM ReservationView rv
        WHERE 1 = 1
        AND (:reservationStatus IS NULL OR rv.reservationSysStatus = :reservationStatus)
        AND (:userId IS NULL OR rv.reservationUserId = :userId)
        AND (:reservationId IS NULL OR rv.reservationId = :reservationId)
        AND (COALESCE(:dateFrom, rv.reservationDateFrom) = rv.reservationDateFrom OR rv.reservationDateFrom >= :dateFrom)
        AND (COALESCE(:dateTo, rv.reservationDateTo) = rv.reservationDateTo OR rv.reservationDateTo <= :dateTo)
        AND (
            :search IS NULL OR :search = ''
            OR LOWER(rv.reservationNumber) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(rv.reservationFlowName) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(rv.reservationFlowSurname) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(rv.reservationFlowEmail) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(CONCAT(rv.reservationFlowName, ' ', rv.reservationFlowSurname))
               LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(rv.agencyName) LIKE LOWER(CONCAT('%', :search, '%'))
        )
    """,
    )
    fun findAllReservationsByParams(
        reservationStatus: ReservationStatus?,
        userId: Long?,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
        reservationId: Long?,
        search: String?,
        pageable: Pageable,
    ): Page<ReservationView>

    fun findByReservationFlowId(reservationFlowId: Long): ReservationView?

    fun findByReservationNumber(reservationNumber: String): ReservationView?

    // ─── Dashboard metrics ────────────────────────────────────────────
    // All counts/sums use `reservationCreatedAt` (when the broker booked it)
    // rather than `reservationDateFrom` (charter start date) because the
    // dashboard answers "how is the agency doing right now", not "how full
    // is the calendar". Cancellations are included in counts (admin sees them
    // in the bookings list) but excluded from revenue (no money earned).

    @Query(
        """
        SELECT COUNT(rv) FROM ReservationView rv
        WHERE rv.reservationCreatedAt >= :from AND rv.reservationCreatedAt < :to
        """,
    )
    fun countByCreatedAtBetween(from: LocalDateTime, to: LocalDateTime): Long

    @Query(
        """
        SELECT COUNT(rv) FROM ReservationView rv
        WHERE rv.reservationSysStatus = :status
        """,
    )
    fun countBySysStatus(status: ReservationStatus): Long

    @Query(
        """
        SELECT COALESCE(SUM(rv.reservationCommission), 0) FROM ReservationView rv
        WHERE rv.reservationCreatedAt >= :from AND rv.reservationCreatedAt < :to
          AND rv.reservationSysStatus <> hr.workspace.boat4you.domains.reservation.enums.ReservationStatus.CANCELLED
        """,
    )
    fun sumCommissionByCreatedAtBetween(from: LocalDateTime, to: LocalDateTime): java.math.BigDecimal

    /**
     * Per-day count for the rolling 7-day window. Returns rows of
     * (yyyy-mm-dd date, count). Used to drive the dashboard bar chart.
     */
    @Query(
        """
        SELECT CAST(rv.reservationCreatedAt AS LocalDate) AS day, COUNT(rv) AS cnt
        FROM ReservationView rv
        WHERE rv.reservationCreatedAt >= :from AND rv.reservationCreatedAt < :to
        GROUP BY CAST(rv.reservationCreatedAt AS LocalDate)
        ORDER BY day
        """,
    )
    fun countPerDayBetween(from: LocalDateTime, to: LocalDateTime): List<Array<Any>>
}
