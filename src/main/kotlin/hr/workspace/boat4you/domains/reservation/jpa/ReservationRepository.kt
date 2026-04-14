package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ReservationRepository : JpaRepository<Reservation, Long> {
    @Query(
        """
        SELECT r
        FROM Reservation r
        JOIN FETCH r.reservationFlow rf
        WHERE r.optionExpiresAt >= :startTime AND r.optionExpiresAt < :endTime
        AND r.status = :status
    """,
    )
    fun findExpiringReservations(
        @Param("startTime") startTime: LocalDateTime,
        @Param("endTime") endTime: LocalDateTime,
        @Param("status") status: OfferStatus,
    ): List<Reservation>

    @Query(
        """
        SELECT r.reservationNumber FROM Reservation r 
        WHERE r.reservationNumber IS NOT NULL AND r.reservationNumber LIKE CONCAT('%/', :year) 
        ORDER BY r.reservationNumber DESC
        LIMIT 1
    """,
    )
    fun findMaxReservationNumberForYear(
        @Param("year") year: String,
    ): String?

    fun findAllBySysStatusAndOptionExpiresAtBefore(
        status: ReservationStatus,
        expiresAt: LocalDateTime,
    ): List<Reservation>

    fun findByReservationFlowIdIn(reservationFlowIds: Collection<Long>): List<Reservation>
}
