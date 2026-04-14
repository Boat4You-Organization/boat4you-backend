package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
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

    fun findAllByYacht(yacht: Yacht): List<Offer>

    /**
     * This can be cached because sequential requests should change on data for different time periods, ie no update of the same data
     */
    @Cacheable("offersByYachtAndStatusCache")
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

    @Modifying
    @Query(
        """
        DELETE FROM Offer o 
        WHERE o.date_to < DATE_ADD(CURRENT_DATE, '-30 day'::interval)
        AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
        """,
        nativeQuery = true,
    )
    fun deleteExpiredOffers()
}
