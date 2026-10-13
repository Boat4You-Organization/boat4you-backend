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

    private fun serviceWith(vararg curated: Pair<Long, Long>) =
        LocationQueryingService(
            mock(LocationRepository::class.java) { inv ->
                if (inv.method.name == "findCuratedSamePlacePairs") curated.map { arrayOf<Any>(it.first, it.second) } else null
            },
            mock(LocationViewRepository::class.java),
            mock(AllLocationViewRepository::class.java),
            mock(YachtLocationsViewRepository::class.java),
        )

    /**
     * V9_79: one marina imported three to five times, all rows renamed to one name and paired with each other (pairs
     * oldest first, as the repository returns them). The list shows ONE row and the group keeps the row of its older
     * pair (V9_78: l-1069 Lavrion, l-2007 Mergellina); an unrelated pair and the containment merge are untouched.
     */
    @Test
    fun `curated pairs of one marina join into one row`() {
        val service =
            serviceWith(
                150L to 1069L, // V9_78
                2099L to 2007L, // V9_78
                150L to 2022L,
                1069L to 2022L,
                209L to 1118L,
                209L to 1301L,
                209L to 2007L,
                209L to 2099L,
                1118L to 1301L,
                1118L to 2007L,
                1118L to 2099L,
                1301L to 2007L,
                1301L to 2099L,
                1865L to 259L,
            )
        val rows =
            listOf(
                marina(150, "Lavrion Main Port", "GR", "Lavrion", 37.7123, 24.056),
                marina(1069, "Lavrion Main Port", "GR", "Lavrion", 37.71099, 24.05771),
                marina(2022, "Lavrion Main Port", "GR", "Lavrion", 37.712764, 24.056905),
                marina(209, "Porto di Mergellina", "IT", "Naples", 40.82769, 14.220927),
                marina(1118, "Porto di Mergellina", "IT", "Naples", 40.82786, 14.22198),
                marina(1301, "Porto di Mergellina", "IT", "Naples", 40.827981, 14.221404),
                marina(2007, "Porto di Mergellina", "IT", "Naples", 40.827043, 14.222574),
                marina(2099, "Porto di Mergellina", "IT", "Naples", 40.826292, 14.224481),
                marina(1865, "D-Marin Marina Lefkas", "GR", "Lefkada"),
                marina(259, "Lefkas, D-Marin", "GR"),
                marina(57, "Trogir, Yachtclub Seget (Marina Baotić)", "HR"),
                marina(1749, "Marina Baotić", "HR", "Seget Donji"),
            )
        service.mergeDualSourceMarinas(rows).map { it.id to it.name } shouldContainExactly
            listOf(
                "l-1069,l-150,l-2022" to "Lavrion Main Port",
                "l-2007,l-2099,l-209,l-1118,l-1301" to "Porto di Mergellina",
                "l-1865,l-259" to "D-Marin Marina Lefkas",
                "l-57,l-1749" to "Trogir, Yachtclub Seget (Marina Baotić)",
            )
    }

    /** A curated pair that touches a containment pair (no name rule pairs "Seget Yacht Club") joins it; the longest name names the row. */
    @Test
    fun `a curated pair joins a containment pair`() {
        val rows =
            listOf(
                marina(57, "Trogir, Yachtclub Seget (Marina Baotić)", "HR"),
                marina(1749, "Marina Baotić", "HR", "Seget Donji"),
                marina(9999, "Seget Yacht Club", "HR", "Seget Donji"),
            )
        serviceWith(9999L to 1749L).mergeDualSourceMarinas(rows).map { it.id to it.name } shouldContainExactly
            listOf("l-57,l-1749,l-9999" to "Trogir, Yachtclub Seget (Marina Baotić)")
    }

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
