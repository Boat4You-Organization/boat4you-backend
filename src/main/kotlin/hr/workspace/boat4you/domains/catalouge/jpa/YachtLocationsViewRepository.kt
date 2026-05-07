package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface YachtLocationsViewRepository : JpaRepository<YachtLocationsView, YachtLocationsViewId> {
    @Query(
        """
        SELECT v.countryId, v.countryCode, COUNT(DISTINCT v.id.yachtId), v.countryName AS countryName, v.continent
        FROM YachtLocationsView v
        GROUP BY v.countryCode, v.countryName, v.continent, v.countryId
    """,
    )
    fun getCountriesCount(): List<Array<Any>>

    @Query(
        """
        SELECT v.id.locationId, v.countryCode, COUNT(DISTINCT v.id.yachtId), v.locationName, v.continent
        FROM YachtLocationsView v
        GROUP BY v.id.locationId, v.countryCode, v.locationName, v.continent
    """,
    )
    fun getLocationsCount(): List<Array<Any>>

    /**
     * Same shape as [getLocationsCount] but scoped to a single region.
     * Used by the "Most popular destinations" internal-link block on
     * `/search?destinations=<region>&did=r-<regionId>` pages — we want
     * the locations that actually live inside the active region, not
     * the country-wide list.
     *
     * The filter joins through `location_region` (the m2m table behind
     * `Location.regions`) — a Location can belong to multiple regions,
     * which the m2m models, so the same location may surface under
     * each. The IN subquery is cheap because `location_region` is
     * indexed on `region_id`.
     */
    @Query(
        """
        SELECT v.id.locationId, v.countryCode, COUNT(DISTINCT v.id.yachtId), v.locationName, v.continent
        FROM YachtLocationsView v
        WHERE v.id.locationId IN (
            SELECT l.id FROM Location l JOIN l.regions r WHERE r.id = :regionId
        )
        GROUP BY v.id.locationId, v.countryCode, v.locationName, v.continent
    """,
    )
    fun getLocationsCountByRegion(regionId: Long): List<Array<Any>>
}
