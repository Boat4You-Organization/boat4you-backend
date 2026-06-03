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
     * IDs of all marinas that are the SAME place as [name] under a spelling/diacritic variant,
     * within the same country. The catalogue holds the same marina twice when providers spell it
     * differently — e.g. "Marina Kastela" (212 yachts) and "Marina Kaštela" (138 yachts) — so a
     * search picking one location id silently drops the other provider's fleet. translate() folds
     * Croatian diacritics (š ž č ć đ) without the unaccent extension. split_part strips any " | city"
     * display suffix (names are bare now, so it's a no-op, but stays defensive). countryCode CAST
     * guards the PG18 untyped-null → bytea trap on the IS NULL branch.
     *
     * Returns IDs, NOT Location entities, on purpose: Location has an @Formula `display_name` that
     * Hibernate cannot resolve from a native `SELECT *` result set (it looks for a "displayName"
     * column and throws). The caller re-fetches via findAllById (HQL → formula-safe).
     */
    @Query(
        value = """
        SELECT l.id FROM location l
        WHERE translate(lower(trim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd')
            = translate(lower(trim(split_part(CAST(:name AS varchar), ' | ', 1))), 'šžčćđ', 'szccd')
          AND (CAST(:countryCode AS varchar) IS NULL OR l.country_code = :countryCode)
        """,
        nativeQuery = true,
    )
    fun findMarinaIdsByFoldedName(
        @Param("name") name: String,
        @Param("countryCode") countryCode: String?,
    ): List<Long>
}
