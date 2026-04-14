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
}
