package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import jakarta.persistence.LockModeType
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface OfferRepository : JpaRepository<Offer, Long> {
    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe 
        LEFT JOIN FETCH oe.extras
        WHERE o.id = :id
    """,
    )
    fun findByIdWithEagerLoad(id: Long): Offer?

    /**
     * Pessimistic-write lock on one offer row for the booking re-check (Deploy 3):
     * serialises concurrent reservation attempts against the same offer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Offer o WHERE o.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Offer?

    fun findAllByYacht(yacht: Yacht): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        WHERE o.yacht.agency.id = :agencyId
    """,
    )
    fun findAllByYachtAgencyId(
        @Param("agencyId") agencyId: Long,
    ): List<Offer>

    /**
     * Cacheable: sequential requests for the same (yacht, statuses)
     * combo over a single TTL window can reuse the prior result —
     * the offer table changes via NauSys/MMK sync which @CacheEvicts.
     *
     * F2-025: explicit SpEL key. Default Spring behaviour wraps the
     * args in a SimpleKey whose hashCode is computed from
     * `yacht.hashCode()` and `statuses.hashCode()`. Yacht has no
     * id-based equals (F2-017 family) so its hashCode is reference
     * identity — every request loads a fresh instance and the cache
     * never hits. The SpEL key composes yacht.id with the structural
     * Set.hashCode of the statuses so two requests with the same
     * yacht and same status set land on the same cache entry.
     */
    @Cacheable("offersByYachtAndStatusCache", key = "#yacht.id + ':' + #statuses.hashCode()")
    @Query(
        """
        SELECT o FROM Offer o
        WHERE o.yacht = :yacht
        AND o.status IN :statuses
    """,
    )
    fun findAllAvailableByYacht(
        yacht: Yacht,
        statuses: Set<OfferStatus>,
    ): List<Offer>

    fun findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe
        WHERE o.yacht = :yacht
        AND o.dateFrom = :dateFrom
        AND o.dateTo = :dateTo
        ORDER BY o.id ASC
    """,
    )
    fun findAllByYachtAndDateFromAndDateTo(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): List<Offer>

    /**
     * Offers whose week OVERLAPS the half-open interval [dateFrom, dateTo). Half-open so a
     * charter that ends on the same day the next begins (Saturday turnaround) is NOT counted
     * as a conflict. Used by availability sync to mark EVERY week a reservation touches as
     * UNAVAILABLE — including multi-week and non-Saturday-aligned reservations that an exact
     * dateFrom/dateTo match silently leaves bookable (the over-availability bug).
     */
    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe
        WHERE o.yacht = :yacht
        AND o.dateFrom < :dateTo
        AND o.dateTo > :dateFrom
        ORDER BY o.id ASC
    """,
    )
    fun findAllByYachtAndDateRangeOverlap(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerExtras oe
        WHERE o.yacht.id = :yachtId
        AND o.dateFrom >= :dateFrom
        AND o.dateTo <= :dateTo
        AND o.type = :offerType
        ORDER BY o.dateFrom, o.clientPrice
    """,
    )
    fun findOffersByYachtIdAndDateFromAndDateToAndOfferType(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("offerType") offerType: OfferType,
    ): List<Offer>

    @Query(
        """
        SELECT o FROM Offer o
        LEFT JOIN FETCH o.offerPaymentPlans
        WHERE o.yacht.id = :yachtId
        AND o.dateFrom = :dateFrom
        AND o.dateTo = :dateTo
        ORDER BY o.id ASC
    """,
    )
    fun findByYachtIdAndDatesWithPaymentPlans(
        @Param("yachtId") yachtId: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
    ): List<Offer>

    @Modifying
    @Query(
        """
        DELETE FROM offer o
        WHERE o.date_to < CURRENT_DATE - INTERVAL '30 days'
        AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
        """,
        nativeQuery = true,
    )
    fun deleteExpiredOffers()
}
