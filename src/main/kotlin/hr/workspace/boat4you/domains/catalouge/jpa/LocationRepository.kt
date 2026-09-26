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
     * The partner's base ids (external_mapping 'Location') of our inland bases - river, canal and lake marinas,
     * location.inland (V9_64). The MMK / NauSys yacht sync and the weekly inventory skip a yacht based there.
     */
    @Query(
        """
        SELECT em.externalId
        FROM ExternalMapping em
        JOIN Location l ON l.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId AND em.type = 'Location' AND l.inland = true
    """,
    )
    fun findInlandExternalIds(externalSystemId: Long): List<Long>

    /**
     * Every location row that is the SAME physical marina as [id] (incl. [id]), for the search's `l-` resolution and
     * the facet counts. The catalogue holds one marina once per provider under spelling/diacritic variants - "Marina
     * Kastela" (212 yachts) and "Marina Kaštela" (138) - so a search picking one id would silently drop the other
     * provider's fleet: same-spelling rows of the same country are siblings (translate() folds Croatian diacritics
     * without the unaccent extension; split_part strips a legacy " | city" suffix) - unless the data says they are
     * different places (26.9.2026 audit B14: both rows have coordinates more than 50 km apart, or no coordinates and two
     * different known cities - the V9_67 functions location_same_area / location_has_area; MarinaPlaces holds the same
     * rule for the location list and the facts). When the same name covers two different places, a row WITHOUT
     * location data cannot be placed and stays alone, and a row with data keeps only the rows proven near it. Plus the curated
     * pairs of location_same_place (V9_67), which no name rule can find ("D-Marin Marina Lefkas" / "Lefkas, D-Marin").
     * One-name-inside-the-other pairs ("Marina Baotić" / "Trogir, Yachtclub Seget (Marina Baotić)") reach the search
     * as the location list's compound did "l-57,l-1749", so they are not repeated here.
     *
     * Returns IDs, NOT Location entities, on purpose: Location has an @Formula `display_name` that Hibernate cannot
     * resolve from a native `SELECT *` result set. The caller re-fetches via findAllById (HQL -> formula-safe).
     */
    @Query(
        value = """
        WITH anchor AS (SELECT * FROM location WHERE id = :id),
        same_name AS (
            SELECT l.*
            FROM anchor a
            JOIN location l
              ON l.country_code = a.country_code
             AND translate(lower(trim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd')
               = translate(lower(trim(split_part(a.name, ' | ', 1))), 'šžčćđ', 'szccd')
        ),
        -- two rows WITH location data that are different places: the name no longer tells which one a row
        -- without data belongs to, so such a row stays alone
        ambiguous AS (
            SELECT EXISTS (
                SELECT 1
                FROM same_name s1
                JOIN same_name s2 ON s1.id < s2.id
                WHERE location_has_area(s1.lat, s1.lon, s1.city) AND location_has_area(s2.lat, s2.lon, s2.city)
                  AND NOT location_same_area(s1.lat, s1.lon, s1.city, s2.lat, s2.lon, s2.city, 50)
            ) AS yes
        )
        SELECT s.id
        FROM same_name s, anchor a, ambiguous amb
        WHERE NOT amb.yes
           OR s.id = a.id
           OR (location_has_area(a.lat, a.lon, a.city) AND location_has_area(s.lat, s.lon, s.city)
               AND location_same_area(a.lat, a.lon, a.city, s.lat, s.lon, s.city, 50))
        UNION
        SELECT CASE WHEN sp.location_id = :id THEN sp.same_as_location_id ELSE sp.location_id END
        FROM location_same_place sp
        WHERE sp.location_id = :id OR sp.same_as_location_id = :id
        """,
        nativeQuery = true,
    )
    fun findSamePlaceMarinaIds(
        @Param("id") id: Long,
    ): List<Long>

    /** The curated same-marina pairs (location_same_place, V9_67): [location_id, same_as_location_id] rows. */
    @Query(value = "SELECT location_id, same_as_location_id FROM location_same_place", nativeQuery = true)
    fun findCuratedSamePlacePairs(): List<Array<Any>>
}
