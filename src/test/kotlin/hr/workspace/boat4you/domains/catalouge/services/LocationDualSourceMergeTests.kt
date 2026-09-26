package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.LocationViewDto
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.AllLocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtLocationsViewRepository
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * The location list's dual-source merge (compound did "l-57,l-1749"), on the production rows of 26.9.2026 (audit B14):
 * "Marina Frapa" (Rogoznica) is inside "Marina Frapa Dubrovnik" by name but 170 km away and must stay its own row —
 * the merged row put Rogoznica boats on the Dubrovnik catamaran landing. Baotić still merges; the curated D-Marin
 * Lefkas pair (location_same_place) merges although no name rule pairs it.
 */
class LocationDualSourceMergeTests {
    private val locationRepository =
        mock(LocationRepository::class.java) { inv ->
            if (inv.method.name == "findCuratedSamePlacePairs") listOf(arrayOf<Any>(1865L, 259L)) else null
        }
    private val service =
        LocationQueryingService(
            locationRepository,
            mock(LocationViewRepository::class.java),
            mock(AllLocationViewRepository::class.java),
            mock(YachtLocationsViewRepository::class.java),
        )

    private fun marina(
        id: Long,
        name: String,
        cc: String,
        city: String? = null,
        lat: Double? = null,
        lon: Double? = null,
    ) = LocationViewDto("l-$id", id, name, LocationType.MARINA, cc, city = city, lat = lat, lon = lon)

    @Test
    fun `Frapa stays apart, Baotic and the curated Lefkas pair merge`() {
        val rows =
            listOf(
                marina(775, "Marina Frapa Dubrovnik", "HR", "Dubrovnik", 42.670, 18.080),
                marina(2029, "Marina Frapa", "HR", "Rogoznica", 43.531, 15.964),
                marina(57, "Trogir, Yachtclub Seget (Marina Baotić)", "HR"),
                marina(1749, "Marina Baotić", "HR", "Seget Donji"),
                marina(1865, "D-Marin Marina Lefkas", "GR", "Lefkada"),
                marina(259, "Lefkas, D-Marin", "GR"),
                LocationViewDto("r-5", 5, "Split", LocationType.REGION, "HR", aliases = listOf("Split region")),
            )
        service.mergeDualSourceMarinas(rows).map { it.id to it.name } shouldContainExactly
            listOf(
                "l-775" to "Marina Frapa Dubrovnik",
                "l-2029" to "Marina Frapa",
                "l-57,l-1749" to "Trogir, Yachtclub Seget (Marina Baotić)",
                "l-1865,l-259" to "D-Marin Marina Lefkas",
                "r-5" to "Split",
            )
    }
}
