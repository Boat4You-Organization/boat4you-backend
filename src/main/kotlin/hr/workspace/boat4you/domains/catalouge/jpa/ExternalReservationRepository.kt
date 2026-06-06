package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface ExternalReservationRepository : JpaRepository<ExternalReservation, Long> {
    fun findAllByYacht(yacht: Yacht): List<ExternalReservation>

    /**
     * Booking re-check (Deploy 3): is the yacht hard-blocked (RESERVATION/SERVICE)
     * for any day overlapping [dateFrom, dateTo)? Half-open, turnaround-safe.
     */
    @Query(
        """
        SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM ExternalReservation r
        WHERE r.yacht.id = :yachtId
          AND r.status IN (:statuses)
          AND r.dateFrom < :dateTo AND r.dateTo > :dateFrom
        """,
    )
    fun existsBlockingOverlap(
        @Param("yachtId") yachtId: Long,
        @Param("statuses") statuses: List<ExternalReservationStatus>,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): Boolean

    @Modifying
    @Query("DELETE FROM ExternalReservation r WHERE r.dateTo < :cutoff")
    fun deleteExpiredReservations(@Param("cutoff") cutoff: LocalDate)

    /**
     * Detail-calendar feed: all reservation rows overlapping calendar [:yearStart, :yearEnd).
     * HALF-OPEN (CRIT-3): a charter that ends on Jan 1 (dateTo == :yearStart) does not
     * bleed into the previous year, and one starting on Jan 1 of the next year
     * (dateFrom == :yearEnd) is excluded — so a turnaround day is never a phantom
     * conflict. Caller computes :yearStart = Jan-1 of year, :yearEnd = Jan-1 of year+1.
     */
    @Query(
        """
        SELECT r FROM ExternalReservation r
        WHERE r.yacht.id = :yachtId
        AND r.dateFrom < :yearEnd AND r.dateTo > :yearStart
    """,
    )
    fun findYachtAvailabilityByYear(
        @Param("yachtId") yachtId: Long,
        @Param("yearStart") yearStart: LocalDate,
        @Param("yearEnd") yearEnd: LocalDate,
    ): List<ExternalReservation>

    /**
     * Detail-calendar feed for a single month. HALF-OPEN (CRIT-3): turnaround
     * day (dateTo == :startDate or dateFrom == :endDate) is NOT a conflict.
     * Caller passes :endDate as the FIRST day AFTER the month (exclusive upper).
     */
    @Query(
        """
        SELECT r FROM ExternalReservation r
        WHERE r.yacht.id = :yachtId
        AND r.dateFrom < :endDate AND r.dateTo > :startDate
    """,
    )
    fun findYachtAvailabilityByAdjustedYearAndMonth(
        @Param("yachtId") yachtId: Long,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): List<ExternalReservation>

    /**
     * Bulk-fetch every LIVE option (MMK + Nausys) that overlaps the given
     * period for any of the listed yachts — used by the admin /offers search
     * to stamp "Option expires: DD.MM.YYYY HH:mm" next to optioned results.
     * Returns rows with non-null AND still-future option_expiration so the
     * caller never has to filter expired ones (partner sync sometimes leaves
     * stale OPTION offer rows behind months after the option lapsed — those
     * must NOT surface as "under option" in the listing). Only OPTION status
     * is considered (RESERVATION / SERVICE don't have an expiry to show).
     * HALF-OPEN (CRIT-3): an option ending on the morning the searched charter
     * begins (turnaround) must NOT count as overlapping, or a reservable yacht
     * would be falsely badged "under option" — the inverse of the honesty goal.
     */
    @Query(
        """
        SELECT r FROM ExternalReservation r
        WHERE r.yacht.id IN :yachtIds
        AND r.status = :status
        AND r.optionExpiration IS NOT NULL
        AND r.optionExpiration > CURRENT_TIMESTAMP
        AND r.dateFrom < :endDate AND r.dateTo > :startDate
    """,
    )
    fun findOptionsByYachtIdsAndPeriod(
        @Param("yachtIds") yachtIds: List<Long>,
        @Param("status") status: ExternalReservationStatus,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): List<ExternalReservation>
}
