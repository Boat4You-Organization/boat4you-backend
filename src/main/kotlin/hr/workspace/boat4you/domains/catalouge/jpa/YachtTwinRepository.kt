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
     * Returns null when no yacht in the group has a FREE future offer (caller
     * then keeps the originally requested id).
     */
    @Query(
        value = """
            SELECT o.yacht_id
            FROM offer o
            JOIN yacht y ON y.id = o.yacht_id
            WHERE o.yacht_id IN (:ids)
              AND o.status = 'FREE'
              AND o.date_from >= :today
            GROUP BY o.yacht_id
            ORDER BY SUM(o.client_price * COALESCE(y.commision_perc / 100.0, y.commision, 0)) DESC,
                     COUNT(*) DESC,
                     o.yacht_id ASC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun pickCanonicalYachtId(
        @Param("ids") ids: List<Long>,
        @Param("today") today: LocalDate,
    ): Long?
}
