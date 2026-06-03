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

    /**
     * All marinas that are the SAME place as [name] under a spelling/diacritic variant, within
     * the same country. The catalogue holds the same marina twice when providers spell it
     * differently — e.g. "Marina Kastela" (212 yachts) and "Marina Kaštela" (138 yachts) — so a
     * search picking one location id silently drops the other provider's fleet. translate()
     * folds Croatian diacritics (š ž č ć đ) without needing the unaccent extension. Always
     * returns at least the marina itself (it folds to its own name). countryCode CAST guards
     * the PG18 untyped-null → bytea trap on the IS NULL branch.
     */
    @Query(
        value = """
        SELECT * FROM location l
        WHERE translate(lower(l.name), 'šžčćđ', 'szccd') = translate(lower(CAST(:name AS varchar)), 'šžčćđ', 'szccd')
          AND (CAST(:countryCode AS varchar) IS NULL OR l.country_code = :countryCode)
        """,
        nativeQuery = true,
    )
    fun findMarinasByFoldedName(
        @Param("name") name: String,
        @Param("countryCode") countryCode: String?,
    ): List<Location>
}
