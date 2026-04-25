package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface ExternalReservationRepository : JpaRepository<ExternalReservation, Long> {
    fun findAllByYacht(yacht: Yacht): List<ExternalReservation>

    @Modifying
    @Query("DELETE FROM ExternalReservation r WHERE r.dateTo < DATEADD(day, -30, CURRENT_DATE)")
    fun deleteExpiredReservations()

    @Query(
        """
        SELECT r FROM ExternalReservation r
        WHERE r.yacht.id = :yachtId
        AND YEAR(r.dateFrom) <= :year AND YEAR(r.dateTo) >= :year
    """,
    )
    fun findYachtAvailabilityByYear(
        @Param("yachtId") yachtId: Long,
        @Param("year") year: Int,
    ): List<ExternalReservation>

    @Query(
        """
        SELECT r FROM ExternalReservation r
        WHERE r.yacht.id = :yachtId
        AND r.dateFrom <= :endDate AND r.dateTo >= :startDate
    """,
    )
    fun findYachtAvailabilityByAdjustedYearAndMonth(
        @Param("yachtId") yachtId: Long,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): List<ExternalReservation>

    /**
     * Bulk-fetch every option (MMK + Nausys) that overlaps the given period
     * for any of the listed yachts — used by the admin /offers search to
     * stamp "Option expires: DD.MM.YYYY HH:mm" next to optioned results.
     * Returns rows with non-null option_expiration so the caller never has
     * to filter nulls. Only OPTION status is considered (RESERVATION /
     * SERVICE don't have an expiry to show).
     */
    @Query(
        """
        SELECT r FROM ExternalReservation r
        WHERE r.yacht.id IN :yachtIds
        AND r.status = :status
        AND r.optionExpiration IS NOT NULL
        AND r.dateFrom <= :endDate AND r.dateTo >= :startDate
    """,
    )
    fun findOptionsByYachtIdsAndPeriod(
        @Param("yachtIds") yachtIds: List<Long>,
        @Param("status") status: ExternalReservationStatus,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): List<ExternalReservation>
}
