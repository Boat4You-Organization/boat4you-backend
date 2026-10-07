package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * Cross-source duplicate ("twin") lookups for canonicalization.
 * See [hr.workspace.boat4you.domains.catalouge.config.TwinCanonicalProperties].
 */
@Repository
interface YachtTwinRepository : JpaRepository<Yacht, Long> {
    /**
     * All EXTERNAL yacht rows that describe the SAME physical boat as [id]
     * (incl. the boat itself). Match key is deliberately conservative — same
     * normalized name + home location + build year + vessel type + length
     * (±0.2 m) — so two genuinely different boats are never merged. Cabins /
     * berths are intentionally excluded: they vary across sources for the same
     * boat (NauSys vs MMK count them differently).
     *
     * Returns no rows when the boat's identity fields are null (→ caller treats
     * as "no twins", i.e. a safe no-op).
     */
    @Query(
        value = """
            SELECT y2.id
            FROM yacht y1
            JOIN yacht y2
              ON y2.entry_type = 'EXTERNAL'
             AND lower(btrim(y2.name)) = lower(btrim(y1.name))
             AND y2.location_id = y1.location_id
             AND y2.build_year  = y1.build_year
             AND y2.vessel_type = y1.vessel_type
             AND abs(coalesce(y2.length, 0) - coalesce(y1.length, 0)) < 0.2
            WHERE y1.id = :id
              AND y1.name IS NOT NULL
              AND y1.location_id IS NOT NULL
              AND y1.build_year IS NOT NULL
        """,
        nativeQuery = true,
    )
    fun findTwinIds(@Param("id") id: Long): List<Long>

    /**
     * Canonical copy of a twin group = the yacht with the highest TOTAL forward
     * broker margin (Σ client_price × commission over FREE weeks from today on).
     * Commission is stored two ways — `commision_perc` (e.g. 20.0 for MMK) or
     * the fractional `commision` (e.g. 0.20 for NauSys) — so both are coalesced
     * to a rate. Tie-break: most free future weeks, then lowest id (stable).
     *
     * Only a copy whose boat page is served (see [SERVED]) can be canonical.
     * Returns null when no such yacht in the group has a FREE future offer
     * (caller then keeps the originally requested id).
     */
    @Query(value = PICK_CANONICAL_BY_MARGIN_SQL, nativeQuery = true)
    fun pickCanonicalYachtId(
        @Param("ids") ids: List<Long>,
        @Param("today") today: LocalDate,
    ): Long?

    /**
     * Canonical copy = the yacht with the MOST distinct free future weeks
     * (coverage), margin as tie-break, then lowest id (stable). Used for manual
     * twin groups whose copies come from different sources with different
     * commissions: there the highest-margin copy is NOT necessarily the one with
     * the fullest calendar (e.g. Desafinado 481@20% NauSys has fewer weeks than
     * 13163@15% MMK), and the product goal is to show the complete calendar.
     *
     * Only a copy whose boat page is served (see [SERVED]) can be canonical.
     * Returns null when no such yacht in the group has a FREE future offer.
     */
    @Query(value = PICK_CANONICAL_BY_COVERAGE_SQL, nativeQuery = true)
    fun pickCanonicalYachtIdByCoverage(
        @Param("ids") ids: List<Long>,
        @Param("today") today: LocalDate,
    ): Long?

    companion object {
        /**
         * The copy's boat page answers 200 (YachtQueryingService.getValidYacht): active, and a partner copy's agency
         * active and not availability-blocked. A retired copy that still has FREE weeks must never win: the live twin's
         * page would turn into its 1502, whose successor (yacht_successor, V9_73) is that live twin - a redirect loop.
         */
        const val SERVED = """
            y.sys_active
            AND (y.entry_type <> 'EXTERNAL' OR (a.active AND NOT a.availability_blocked))
        """

        const val PICK_CANONICAL_BY_MARGIN_SQL = """
            SELECT o.yacht_id
            FROM offer o
            JOIN yacht y       ON y.id = o.yacht_id
            LEFT JOIN agency a ON a.id = y.agency_id
            WHERE o.yacht_id IN (:ids)
              AND $SERVED
              AND o.status = 'FREE'
              AND o.date_from >= :today
            GROUP BY o.yacht_id
            ORDER BY SUM(o.client_price * COALESCE(y.commision_perc / 100.0, y.commision, 0)) DESC,
                     COUNT(*) DESC,
                     o.yacht_id ASC
            LIMIT 1
        """

        const val PICK_CANONICAL_BY_COVERAGE_SQL = """
            SELECT o.yacht_id
            FROM offer o
            JOIN yacht y       ON y.id = o.yacht_id
            LEFT JOIN agency a ON a.id = y.agency_id
            WHERE o.yacht_id IN (:ids)
              AND $SERVED
              AND o.status = 'FREE'
              AND o.date_from >= :today
            GROUP BY o.yacht_id
            ORDER BY COUNT(DISTINCT o.date_from) DESC,
                     SUM(o.client_price * COALESCE(y.commision_perc / 100.0, y.commision, 0)) DESC,
                     o.yacht_id ASC
            LIMIT 1
        """
    }
}
