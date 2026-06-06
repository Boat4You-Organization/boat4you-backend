package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
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

    /**
     * Other reservations holding the SAME offer row. offer is @ManyToOne, so many
     * reservation flows can point at one offer; auto-release uses this to avoid
     * freeing an offer a different live reservation still holds (CRIT-1 guard).
     */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.reservationFlow.offer.id = :offerId AND r.sysStatus IN (:statuses)
        """,
    )
    fun findActiveByOfferId(
        @Param("offerId") offerId: Long,
        @Param("statuses") statuses: List<ReservationStatus>,
    ): List<Reservation>

    fun findByReservationFlowIdIn(reservationFlowIds: Collection<Long>): List<Reservation>

    /**
     * Rezervacije koje periodic sync job provjera za yacht-swap detection:
     * aktivne (OPTION + RESERVATION), imaju externalId iz partner sustava,
     * charter dateFrom nije davno prošao (skipamo historijsku DB bloat).
     * Sort po dateFrom ASC — near-term rezervacije (veći rizik swap-a)
     * processed first.
     */
    @Query(
        """
        SELECT r FROM Reservation r
        JOIN FETCH r.reservationFlow rf
        JOIN FETCH rf.yacht y
        JOIN FETCH y.agency a
        WHERE r.sysStatus IN (:statuses)
        AND r.externalId IS NOT NULL
        AND r.dateFrom >= :cutoff
        ORDER BY r.dateFrom ASC
    """,
    )
    fun findActiveForPartnerSync(
        @Param("statuses") statuses: List<ReservationStatus>,
        @Param("cutoff") cutoff: LocalDateTime,
    ): List<Reservation>

    @Modifying
    @Transactional
    @Query(
        value = "UPDATE reservation_flow SET yacht_id = :newYachtId WHERE id = :reservationFlowId",
        nativeQuery = true,
    )
    fun updateYachtOnSwap(
        @Param("reservationFlowId") reservationFlowId: Long,
        @Param("newYachtId") newYachtId: Long,
    ): Int

    /**
     * Confirmed reservations whose pickup falls within `[startTime, endTime)`.
     * Used by [PreCharterReminderJob] to find tomorrow's bookings (status =
     * RESERVATION) and dispatch the "tomorrow you sail" email.
     */
    @Query(
        """
        SELECT r FROM Reservation r
        JOIN FETCH r.reservationFlow rf
        JOIN FETCH rf.yacht y
        WHERE r.sysStatus = :status
        AND r.dateFrom >= :startTime AND r.dateFrom < :endTime
    """,
    )
    fun findConfirmedStartingBetween(
        @Param("status") status: ReservationStatus,
        @Param("startTime") startTime: LocalDateTime,
        @Param("endTime") endTime: LocalDateTime,
    ): List<Reservation>
}
