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
    """,
    )
    fun findAllReservationsByParams(
        reservationStatus: ReservationStatus?,
        userId: Long?,
        dateFrom: LocalDateTime?,
        dateTo: LocalDateTime?,
        reservationId: Long?,
        pageable: Pageable,
    ): Page<ReservationView>

    fun findByReservationFlowId(reservationFlowId: Long): ReservationView?
}
