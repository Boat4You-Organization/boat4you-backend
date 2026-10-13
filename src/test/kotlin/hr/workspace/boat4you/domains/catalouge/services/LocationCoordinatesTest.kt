package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.LocationCoordinates
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.LocationViewDto
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.AllLocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtLocationsViewRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * LocationDto.lat / lon (Mario 13.10.2026: "Marina Kaštela | Kaštel Gomilica" sent the web's map to a text search that
 * found no marina) and the location list's lat / lon, on the production rows of the Kaštela pair: 63 "Marina Kastela"
 * (NauSys 54, no city) and 1935 "Marina Kaštela" (MMK 119, Kaštel Gomilica), 280 m apart in the same marina.
 */
class LocationCoordinatesTest {
    private val json = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()

    private fun location(
        id: Long,
        lat: String?,
        lon: String?,
    ) = Location().apply {
        this.id = id
        name = "Marina Kaštela"
        city = "Kaštel Gomilica"
        displayName = "Marina Kaštela | Kaštel Gomilica"
        countryCode = "HR"
        this.lat = lat?.let(::BigDecimal)
        this.lon = lon?.let(::BigDecimal)
    }

    @Test
    fun `the boat page's location carries the marina's coordinates, none when unknown or 0,0`() {
        assertEquals(
            LocationDto("l-1935", "Marina Kaštela | Kaštel Gomilica", "HR", 43.546021, 16.401395),
            location(1935, "43.546021", "16.401395").toDto(),
        )
        assertNull(location(1, null, "16.4").toDto().lat)
        assertNull(location(1, "43.5", null).toDto().lon)
        val zero = location(1, "0", "0.000000").toDto()
        assertNull(zero.lat)
        assertNull(zero.lon)
        assertTrue(json.writeValueAsString(location(1935, "43.546021", "16.401395").toDto()).contains("\"lat\":43.546021,\"lon\":16.401395"))
    }

    @Test
    fun `search cards take the coordinates by location id, bare or l- prefixed`() {
        val coordinates = mapOf(63L to LocationCoordinates(43.54548, 16.4048))
        val card = LocationDto("63", "Marina Kastela", "HR")
        assertEquals(LocationDto("63", "Marina Kastela", "HR", 43.54548, 16.4048), card.withCoordinates(coordinates))
        assertEquals(43.54548, LocationDto("l-63", "Marina Kastela", "HR").withCoordinates(coordinates).lat)
        val unknown = LocationDto("1935", "Marina Kaštela | Kaštel Gomilica", "HR")
        assertSame(unknown, unknown.withCoordinates(coordinates))
        val custom = LocationDto("C-54", "Croatia", "HR")
        assertSame(custom, custom.withCoordinates(coordinates))
        val none = LocationDto(null, null, null)
        assertSame(none, none.withCoordinates(coordinates))
    }

    @Test
    fun `the location list shows a marina's coordinates, never its city, and countries and regions stay byte for byte`() {
        val marina = LocationViewDto("l-1935", 1935, "Marina Kaštela", LocationType.MARINA, "HR", city = "Kaštel Gomilica", lat = 43.546021, lon = 16.401395)
        val marinaJson = json.writeValueAsString(marina)
        assertTrue(marinaJson.contains("\"lat\":43.546021"), marinaJson)
        assertTrue(marinaJson.contains("\"lon\":16.401395"), marinaJson)
        assertFalse(marinaJson.contains("Gomilica"), marinaJson)
        val region = LocationViewDto("r-5", 5, "Split", LocationType.REGION, "HR")
        assertEquals(
            """{"id":"r-5","realId":5,"name":"Split","locationType":"REGION","countryCode":"HR"}""",
            json.writeValueAsString(region),
        )
    }

    @Test
    fun `the curated Kastela pair is one row of the location list, with the coordinates of the row that stays`() {
        val repository =
            mock(LocationRepository::class.java) { inv ->
                if (inv.method.name == "findCuratedSamePlacePairs") listOf(arrayOf<Any>(63L, 1935L)) else null
            }
        val service =
            LocationQueryingService(
                repository,
                mock(LocationViewRepository::class.java),
                mock(AllLocationViewRepository::class.java),
                mock(YachtLocationsViewRepository::class.java),
            )
        // the names after LOCATION_KASTELA_fix (same spelling) and before it (diacritics only): one row either way
        for (name63 in listOf("Marina Kaštela", "Marina Kastela")) {
            val merged =
                service.mergeDualSourceMarinas(
                    listOf(
                        LocationViewDto("l-63", 63, name63, LocationType.MARINA, "HR", lat = 43.54548, lon = 16.4048),
                        LocationViewDto("l-1935", 1935, "Marina Kaštela", LocationType.MARINA, "HR", city = "Kaštel Gomilica", lat = 43.546021, lon = 16.401395),
                    ),
                )
            assertEquals(1, merged.size, name63)
            assertEquals("l-1935,l-63", merged.single().id)
            assertEquals("Marina Kaštela", merged.single().name)
            assertEquals(43.546021, merged.single().lat)
            assertEquals(16.401395, merged.single().lon)
        }
    }
}
