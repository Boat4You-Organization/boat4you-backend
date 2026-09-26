package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RegionRepository : JpaRepository<Region, Long> {
    @Query(
        "SELECT r FROM Region r" +
            " JOIN ExternalMapping em ON r.id = em.systemId" +
            " WHERE em.externalSystem.id = :externalSystemId" +
            " AND em.externalId = :externalId",
    )
    fun findByNausysRegionId(
        externalId: Long,
        externalSystemId: Long = ExternalSystemEnum.NAUSYS.value.toLong(),
    ): Region?

    fun findByName(name: String): Region?

    /**
     * The region that already knows [name] as one of its spellings (region_alias, V9_68) — so a partner area whose
     * name is an old or other-partner spelling joins the right region instead of creating a duplicate.
     */
    @Query(
        value = """
            SELECT r.* FROM region r
            JOIN region_alias a ON a.region_id = r.id
            WHERE lower(a.alias) = lower(btrim(CAST(:name AS varchar)))
            ORDER BY r.id
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findByAlias(
        @Param("name") name: String,
    ): Region?
}
