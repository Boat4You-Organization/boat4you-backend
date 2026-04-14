package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface LocationRepository : JpaRepository<Location, Long> {
    @Query(
        """
        SELECT l FROM Location l
        JOIN Country c ON l.country.code2 = c.code2
        WHERE c.id = :countryId
    """,
    )
    fun findMarinasByCountryId(
        @Param("countryId") countryId: Int,
    ): List<Location>

    @Query(
        """
        SELECT l FROM Location l
        JOIN LocationRegion lr ON l.id = lr.id.locationId
        WHERE lr.id.regionId = :regionId
    """,
    )
    fun findMarinasByRegionId(
        @Param("regionId") regionId: Int,
    ): List<Location>

    @Query(
        """
        SELECT l 
        FROM Location l
        JOIN ExternalMapping em ON l.id = em.systemId
        WHERE em.externalId = :externalId AND em.externalSystem.id = :externalSystemId AND em.type = 'Location'
    """,
    )
    fun findByExternalIdAndExternalSystemId(
        externalId: Long,
        externalSystemId: Long,
    ): Location?

    fun findByNameIgnoreCase(name: String): Location?
}
