package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.services.MarinaPlaces.Marina
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which location rows are one physical marina (26.9.2026 audit B13 / B14), on the production names of that day. */
class MarinaPlacesTests {
    private val frapaRogoznica = Marina(2029, "Marina Frapa", "HR", "Rogoznica", 43.531, 15.964)
    private val frapaDubrovnik = Marina(775, "Marina Frapa Dubrovnik", "HR", "Dubrovnik", 42.670, 18.080)
    private val baoticMmk = Marina(1749, "Marina Baotić", "HR", "Seget Donji")
    private val baoticNausys = Marina(57, "Trogir, Yachtclub Seget (Marina Baotić)", "HR")
    private val kastelaNausys = Marina(63, "Marina Kastela", "HR")
    private val kastelaMmk = Marina(1935, "Marina Kaštela", "HR", "Kaštel Gomilica", 43.5516, 16.3627)
    private val lefkasMmk = Marina(1865, "D-Marin Marina Lefkas", "GR", "Lefkada")
    private val lefkasNausys = Marina(259, "Lefkas, D-Marin", "GR")
    private val portLefkas = Marina(1534, "Port of Lefkas", "GR", "Lefkada")

    @Test
    fun `Frapa Rogoznica is not Frapa Dubrovnik - the name is inside the other, the marina is 170 km away`() {
        MarinaPlaces.containmentPairs(listOf(frapaRogoznica, frapaDubrovnik)).shouldBeEmpty()
        // without coordinates the cities still disagree
        MarinaPlaces
            .containmentPairs(listOf(frapaRogoznica.copy(lat = null, lon = null), frapaDubrovnik.copy(lat = null, lon = null)))
            .shouldBeEmpty()
        val places = MarinaPlaces.placeIds(listOf(frapaRogoznica, frapaDubrovnik))
        (places.getValue(2029) == places.getValue(775)) shouldBe false
    }

    @Test
    fun `dual-source rows - one name inside the other, same spelling, curated pairs`() {
        MarinaPlaces.containmentPairs(listOf(baoticMmk, baoticNausys)).map { it.first.id to it.second.id } shouldContainExactly
            listOf(1749L to 57L)
        val all = listOf(baoticMmk, baoticNausys, kastelaNausys, kastelaMmk, lefkasMmk, lefkasNausys, portLefkas)
        val places = MarinaPlaces.placeIds(all, curated = listOf(1865L to 259L))
        places.getValue(1749) shouldBe 57L
        places.getValue(57) shouldBe 57L
        places.getValue(1935) shouldBe 63L
        places.getValue(1865) shouldBe 259L
        // the town quay next door stays its own place
        places.getValue(1534) shouldBe 1534L
    }

    @Test
    fun `a bare city name inside several marinas pairs with none`() {
        val ibiza = Marina(1, "Ibiza", "ES")
        val marinas = listOf(ibiza, Marina(2, "Marina Ibiza", "ES"), Marina(3, "Club Nautico Ibiza", "ES"))
        MarinaPlaces.containmentPairs(marinas).shouldBeEmpty()
    }

    @Test
    fun `one name per place - the longer name, then the spelling with diacritics`() {
        MarinaPlaces.representative(listOf(baoticMmk, baoticNausys)).id shouldBe 57L
        MarinaPlaces.representative(listOf(kastelaNausys, kastelaMmk)).id shouldBe 1935L
    }

    @Test
    fun `same spelling far apart stays two places, a row without data cannot be placed and stays alone`() {
        val a = Marina(1, "Port of Kos", "GR", lat = 36.89, lon = 27.29)
        val b = Marina(2, "Port of Kos", "GR", lat = 38.00, lon = 23.70)
        val near = a.copy(id = 3, lat = 36.90)
        val unknown = Marina(4, "Port of Kos", "GR")
        val places = MarinaPlaces.placeIds(listOf(a, b, near, unknown))
        (places.getValue(1) == places.getValue(2)) shouldBe false
        places.getValue(3) shouldBe 1L
        places.getValue(4) shouldBe 4L
        // without the far row the spelling is unambiguous again
        MarinaPlaces.placeIds(listOf(a, near, unknown)).values.toSet() shouldBe setOf(1L)
    }
}
