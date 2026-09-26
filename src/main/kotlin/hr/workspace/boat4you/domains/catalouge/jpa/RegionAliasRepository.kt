package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RegionAliasRepository : JpaRepository<RegionAlias, Long> {
    /**
     * Record a partner's spelling of a region (idempotent: an existing spelling only gets its last_seen_at and source
     * refreshed). Never touches region.name.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO region_alias (region_id, alias, source)
            VALUES (:regionId, btrim(CAST(:alias AS varchar)), :source)
            ON CONFLICT (region_id, lower(alias)) DO UPDATE SET last_seen_at = now(), source = EXCLUDED.source
        """,
        nativeQuery = true,
    )
    fun record(
        @Param("regionId") regionId: Int,
        @Param("alias") alias: String,
        @Param("source") source: String,
    ): Int
}
