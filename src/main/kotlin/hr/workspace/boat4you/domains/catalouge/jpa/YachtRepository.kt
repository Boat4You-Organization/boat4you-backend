package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface YachtRepository : JpaRepository<Yacht, Long> {
    fun findAllByAgencyAndIdNotIn(
        agency: Agency,
        ids: List<Long>,
    ): List<Yacht>

    @Query(
        """
        SELECT y
        FROM Yacht y
        JOIN ExternalMapping em
            ON em.systemId = y.id
        WHERE y.agency = :agency
        AND em.type = 'Yacht'
        AND em.externalId NOT IN :externalIds
    """,
    )
    fun findAllByAgencyAndExternalIdNotIn(
        agency: Agency,
        externalIds: List<Long>,
    ): List<Yacht>

    fun findAllByAgencyId(agencyId: Long): List<Yacht>

    @Query(
        """
        SELECT new hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto(y.id, y.name, y.excludeDiscount)
        FROM Yacht y 
        WHERE y.agency.id = :agencyId
        ORDER BY y.name
    """,
    )
    fun findAllByAgencyIdToDto(agencyId: Long): List<AgencyYachtDto>

    @Query(
        """
        SELECT y
        FROM Yacht y
        JOIN FETCH y.reservationOptions ro
        JOIN FETCH y.agency a
        WHERE y.agency = :agency
        AND y.sysActive = true
        AND ro.dateTo > CURRENT_DATE
    """,
    )
    fun findWithReservationOptionsByAgency(agency: Agency): List<Yacht>

    @Query(
        """
        SELECT DISTINCT y.vesselType
        FROM Yacht y
        WHERE y.sysActive = true AND y.agency.active = true
        """,
    )
    fun getUsedVesselTypes(): List<Int>

    @Query(
        """
        SELECT y.vesselType AS vesselType, COUNT(*) AS count
        FROM Yacht y
        WHERE y.sysActive = true AND y.agency.active = true
        GROUP BY y.vesselType
        """,
    )
    fun getVesselTypeYachtCount(): List<Array<Any>>

    @Query(
        """
        SELECT DISTINCT yct.type
        FROM YachtCharterType yct
        JOIN Yacht y ON yct.yacht.id = y.id
        WHERE y.sysActive = true AND y.agency.active = true
        """,
    )
    fun getUsedCharterTypes(): List<Int>

    @Query(
        """
        SELECT y
        FROM Yacht y 
        JOIN ExternalMapping em ON y.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId
        AND em.externalId = :externalId
        AND em.type = 'Yacht'
    """,
    )
    fun findByExternalIdAndExternalSystemId(
        externalId: Long,
        externalSystemId: Long,
    ): Yacht?

    @Query(
        """
        SELECT y
        FROM Yacht y 
        JOIN ExternalMapping em ON y.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId
        AND em.externalId IN :externalIds
        AND em.type = 'Yacht'
    """,
    )
    fun findByExternalIdsAndExternalSystemId(
        externalIds: List<Long>,
        externalSystemId: Long,
    ): List<Yacht>

    fun countYachtsByModelId(modelId: Long): Int

    fun findByModelId(modelId: Long): List<Yacht>

    /**
     * Admin replacement-flow search. Returns yachts that the broker can
     * legitimately assign to a customer when the original yacht broke down
     * and the agency swapped the customer onto a different boat — WITHOUT
     * requiring an available `offer` row for the exact search period.
     *
     * Compared to `yacht_search_view` (the regular customer/admin search
     * source), this query:
     *   - Surfaces yachts even when the partner has already sold the whole
     *     period (so there's no `offer` row) as long as the yacht has an
     *     `external_reservation` overlapping the search window.
     *   - Matches by the yacht's home `location_id` OR any offer's
     *     `location_from` in the requested marinas.
     *   - Returns the yacht's AVERAGE per-day price across its active
     *     offers in the destination as a hint; null when no offer at all
     *     (admin overrides total price manually in the wizard Step 2).
     *
     * Intentionally native SQL — the LATERAL join + the OR-across-tables
     * predicate are painful to express in JPQL/Criteria. Result rows are
     * mapped in YachtQueryingService.getYachtsForReplacement.
     */
    @Query(
        value = """
            SELECT
              y.id                                   AS id,
              y.name                                 AS yacht_name,
              y.build_year                           AS build_year,
              y.max_persons                          AS max_persons,
              y.cabins                               AS cabins,
              y.berths                               AS berths,
              y.length                               AS length,
              y.wc                                   AS wc,
              y.engine_power                         AS engine_power,
              y.vessel_type                          AS vessel_type,
              y.mainsail_type                        AS mainsail_type,
              y.model_id                             AS model_id,
              m.name                                 AS model_name,
              mf.id                                  AS manufacturer_id,
              mf.name                                AS manufacturer_name,
              y.main_image_id                        AS main_image_id,
              a.id                                   AS agency_id,
              a.name                                 AS agency_name,
              y.location_id                          AS location_id,
              l.name                                 AS location_name,
              l.country_code                         AS location_country,
              avg_price.avg_per_day                  AS avg_client_price,
              (avg_price.avg_per_day IS NULL
               AND EXISTS (SELECT 1 FROM external_reservations er
                           WHERE er.yacht_id = y.id
                             AND er.date_from <= :endDate
                             AND er.date_to   >= :startDate))
                                                     AS only_external_reservation
            FROM yacht y
            JOIN agency a       ON a.id = y.agency_id AND a.active = true
            LEFT JOIN model m   ON m.id = y.model_id
            LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
            LEFT JOIN location l      ON l.id = y.location_id
            LEFT JOIN LATERAL (
              SELECT AVG(o.client_price / NULLIF(o.date_to - o.date_from, 0)) AS avg_per_day
              FROM offer o
              WHERE o.yacht_id = y.id
                AND o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')
                AND (:locationIdsEmpty = true OR o.location_from IN (:locationIds))
            ) avg_price ON TRUE
            WHERE y.sys_active = true
              AND y.entry_type = 'EXTERNAL'
              AND (
                :locationIdsEmpty = true
                OR y.location_id IN (:locationIds)
                OR EXISTS (
                  SELECT 1 FROM offer o
                  WHERE o.yacht_id = y.id
                    AND o.location_from IN (:locationIds)
                )
              )
              AND (:agencyIdsEmpty = true OR y.agency_id IN (:agencyIds))
              AND (:vesselTypesEmpty = true OR y.vessel_type IN (:vesselTypes))
              AND (
                avg_price.avg_per_day IS NOT NULL
                OR EXISTS (
                  SELECT 1 FROM external_reservations er
                  WHERE er.yacht_id = y.id
                    AND er.date_from <= :endDate
                    AND er.date_to   >= :startDate
                )
              )
            ORDER BY y.name
            LIMIT :pageSize OFFSET :pageOffset
        """,
        nativeQuery = true,
    )
    fun findForReplacementSearch(
        locationIds: List<Long>,
        locationIdsEmpty: Boolean,
        agencyIds: List<Long>,
        agencyIdsEmpty: Boolean,
        vesselTypes: List<String>,
        vesselTypesEmpty: Boolean,
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate,
        pageSize: Int,
        pageOffset: Int,
    ): List<ReplacementSearchRow>

    /**
     * Count version of [findForReplacementSearch] — needed for paging since
     * the main query is already ORDER BY + LIMIT. Kept in sync with the
     * main query's WHERE branches.
     */
    @Query(
        value = """
            SELECT COUNT(DISTINCT y.id)
            FROM yacht y
            JOIN agency a ON a.id = y.agency_id AND a.active = true
            WHERE y.sys_active = true
              AND y.entry_type = 'EXTERNAL'
              AND (
                :locationIdsEmpty = true
                OR y.location_id IN (:locationIds)
                OR EXISTS (
                  SELECT 1 FROM offer o
                  WHERE o.yacht_id = y.id
                    AND o.location_from IN (:locationIds)
                )
              )
              AND (:agencyIdsEmpty = true OR y.agency_id IN (:agencyIds))
              AND (:vesselTypesEmpty = true OR y.vessel_type IN (:vesselTypes))
              AND (
                EXISTS (
                  SELECT 1 FROM offer o
                  WHERE o.yacht_id = y.id
                    AND o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')
                    AND (:locationIdsEmpty = true OR o.location_from IN (:locationIds))
                )
                OR EXISTS (
                  SELECT 1 FROM external_reservations er
                  WHERE er.yacht_id = y.id
                    AND er.date_from <= :endDate
                    AND er.date_to   >= :startDate
                )
              )
        """,
        nativeQuery = true,
    )
    fun countForReplacementSearch(
        locationIds: List<Long>,
        locationIdsEmpty: Boolean,
        agencyIds: List<Long>,
        agencyIdsEmpty: Boolean,
        vesselTypes: List<String>,
        vesselTypesEmpty: Boolean,
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate,
    ): Long
}

/**
 * Result projection for [YachtRepository.findForReplacementSearch]. Spring
 * Data maps native SQL columns onto these getters by alias name.
 */
interface ReplacementSearchRow {
    val id: Long
    val yachtName: String?
    val buildYear: Short?
    val maxPersons: Short?
    val cabins: Short?
    val berths: Short?
    val length: java.math.BigDecimal?
    val wc: Short?
    val enginePower: Short?
    val vesselType: String?
    val mainsailType: String?
    val modelId: Long?
    val modelName: String?
    val manufacturerId: Long?
    val manufacturerName: String?
    val mainImageId: Long?
    val agencyId: Long?
    val agencyName: String?
    val locationId: Long?
    val locationName: String?
    val locationCountry: String?
    /** Average per-day price across the yacht's active offers in the destination; null if no offer. */
    val avgClientPrice: java.math.BigDecimal?
    /** True when the only reason the yacht is surfaced is an overlapping `external_reservation` (no offer available). */
    val onlyExternalReservation: Boolean
}
