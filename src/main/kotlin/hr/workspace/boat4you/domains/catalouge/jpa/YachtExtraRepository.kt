package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface YachtExtraRepository : JpaRepository<YachtExtra, Long> {
    /**
     * F2-007: explicit `key = "#yacht.id"` because `Yacht` has no
     * id-based `equals`/`hashCode` (does not extend AbstractEntity,
     * see F2-017). Without this SpEL key Spring falls back to the
     * arg itself as the key, and `Yacht.hashCode()` is reference
     * identity — every request loads a fresh Yacht instance so the
     * cache effectively never hits. With `#yacht.id` the cache key
     * is the persisted Long id, which is stable across requests.
     */
    @Cacheable("yachtExtrasCache", key = "#yacht.id")
    @EntityGraph(attributePaths = ["extras"])
    fun findAllByYacht(yacht: Yacht): List<YachtExtra>

    @Query(
        """
        WITH prices AS (
            SELECT id, price,
                   CASE
                       WHEN extras_id IS NOT NULL THEN extras_id::varchar
                       ELSE name
                   END as group_key,
                   MIN(price) OVER (
                       PARTITION BY CASE
                           WHEN extras_id IS NOT NULL THEN extras_id::varchar
                           ELSE name
                       END
                   ) as calc_price
            FROM yacht_extras
            WHERE yacht_id = :yachtId
                -- Period overlap (not strict containment): show extras whose
                -- sailing window intersects the user-selected range. Strict
                -- containment used to surface period-specific APA / comfort
                -- packs for every booking window, since MMK splits seasonal
                -- pricing into multiple rows with disjoint sailing dates.
                AND (CAST(:dateFrom AS date) IS NULL OR valid_from <= CAST(:dateTo AS date))
                AND (CAST(:dateTo AS date) IS NULL OR valid_to >= CAST(:dateFrom AS date))
                AND CASE
                    WHEN :validForExtBaseIds IS NULL THEN true
                    WHEN array_length(:validForExtBaseIds, 1) IS NULL THEN true
                    WHEN valid_for_bases IS NULL THEN true
                    ELSE :validForExtBaseIds && valid_for_bases
                END
        )
        SELECT MIN(id)
        FROM prices
        WHERE price = calc_price
        GROUP BY group_key
    """,
        nativeQuery = true,
    )
    fun findYachtExtraIdsGroupedByYacht(
        yachtId: Long,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        validForExtBaseIds: Array<Long>?,
    ): List<Long>

    @Query(
        """
        SELECT ye
        FROM YachtExtra ye
        WHERE ye.yacht = :yacht
        AND ye.id IN :yachtExtraIds
    """,
    )
    fun findGroupedByYacht(
        yacht: Yacht,
        yachtExtraIds: List<Long>,
    ): List<YachtExtra>

    // Period-aware fallback when the yacht has no location (OSH/MMK legacy —
    // Fortuna 5533, Sea Dreams 3117, DESSUS 3116...). Same sailing-window
    // overlap filter as the grouped query, minus the base-scoping — so
    // period-specific APA / packs don't leak into bookings outside their
    // sailing range. Groups by extras_id / name to dedupe identical entries.
    @Query(
        """
        WITH prices AS (
            SELECT id, price,
                   CASE
                       WHEN extras_id IS NOT NULL THEN extras_id::varchar
                       ELSE name
                   END as group_key,
                   MIN(price) OVER (
                       PARTITION BY CASE
                           WHEN extras_id IS NOT NULL THEN extras_id::varchar
                           ELSE name
                       END
                   ) as calc_price
            FROM yacht_extras
            WHERE yacht_id = :yachtId
                AND (CAST(:dateFrom AS date) IS NULL OR valid_from <= CAST(:dateTo AS date))
                AND (CAST(:dateTo AS date) IS NULL OR valid_to >= CAST(:dateFrom AS date))
        )
        SELECT MIN(id)
        FROM prices
        WHERE price = calc_price
        GROUP BY group_key
    """,
        nativeQuery = true,
    )
    fun findYachtExtraIdsByYachtAndPeriod(
        yachtId: Long,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ): List<Long>
}
