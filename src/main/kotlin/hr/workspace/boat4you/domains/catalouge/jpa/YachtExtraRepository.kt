package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface YachtExtraRepository : JpaRepository<YachtExtra, Long> {
    @Cacheable("yachtExtrasCache")
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
                AND valid_from <= COALESCE(CAST(:dateFrom AS date), valid_from)
                AND valid_to >= COALESCE(CAST(:dateTo AS date), valid_to)
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
}
