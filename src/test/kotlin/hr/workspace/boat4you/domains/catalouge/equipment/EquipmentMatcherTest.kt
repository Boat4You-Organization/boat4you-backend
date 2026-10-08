package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * EquipmentMatcher rules (equipment audit 8.10.2026, FIX_CONTRACT 6), on the real R__1_05 v2 catalogue where a rule is
 * about our keys and on two-row catalogues where it is about the matcher itself.
 */
class EquipmentMatcherTest {
    private val matcher = EquipmentMatcher(EquipmentCatalogueFixture.catalogue())

    private fun label(name: String?): String? = matcher.best(name)?.labelCode

    private fun row(
        id: Long,
        label: String,
        keys: String,
        mergedInto: Long? = null,
    ) = Equipment().apply {
        this.id = id
        labelCode = label
        name = label
        category = CategoryEnum.DECK
        matchKeys = keys
        mergedIntoId = mergedInto
    }

    @Test
    fun `a compound prefix is not the word - lifebuoy, liferaft, watermaker, anchorage, logge`() {
        val life = EquipmentMatcher(listOf(row(1, "life", "token-match:life")))
        assertNull(life.best("Lifebuoy"))
        assertNull(life.best("Liferaft"))
        assertNull(EquipmentMatcher(listOf(row(1, "water", "token-match:water"))).best("Watermaker"))
        assertNull(EquipmentMatcher(listOf(row(1, "log", "token-match:log"))).best("Logge"))
        assertEquals("life-buoy", label("Lifebuoy"))
        assertEquals("liferaft", label("Liferaft"))
        assertEquals("water-maker", label("Watermaker"))
        assertEquals("water-maker", label("Watermaker - desalinator"))
        assertEquals("waste-tank", label("Black Water Tank"))
        assertNull(label("Water pump"))
    }

    @Test
    fun `main-anchor is reachable - not-anchorage no longer vetoes every anchor`() {
        assertEquals("main-anchor", label("Main anchor"))
        assertEquals("main-anchor", label("Anchor"))
        assertEquals("main-anchor", label("Anchor with chain"), "not:anchor chain needs the two words in a row")
        assertEquals("anchor-line", label("Anchor chain"))
        assertEquals("spare-anchor", label("Spare anchor"))
        assertNull(label("Anchorage tax"))
        val anchor = EquipmentMatcher(listOf(row(1, "anchor", "token-match:anchor, not:anchorage")))
        assertEquals("anchor", anchor.best("Anchor")?.labelCode)
        assertNull(anchor.best("Anchorage"))
    }

    @Test
    fun `not-finder vetoes Fish finder but never fender`() {
        assertEquals("fenders", label("Fenders"))
        assertEquals("fenders", label("Fender"))
        assertNull(label("Fish finder"))
        val fenders = EquipmentMatcher(listOf(row(1, "fenders", "token-match:fender, not:finder")))
        assertEquals("fenders", fenders.best("Fender")?.labelCode)
        assertNull(fenders.best("Fish finder"))
    }

    @Test
    fun `plural and typo tolerance, typos only on long words with the same first letter`() {
        assertEquals(0.99, EquipmentMatcher.tokenSimilarity("towel", "towels", typos = true))
        assertEquals(0.99, EquipmentMatcher.tokenSimilarity("fan", "fans", typos = true))
        assertEquals(0.0, EquipmentMatcher.tokenSimilarity("toilet", "toilettes", typos = true))
        assertEquals(0.9, EquipmentMatcher.tokenSimilarity("cooker", "coocker", typos = true))
        assertEquals(0.0, EquipmentMatcher.tokenSimilarity("cooker", "coocker", typos = false), "no typos in not: keys")
        assertEquals(0.0, EquipmentMatcher.tokenSimilarity("internet", "internal", typos = true), "distance 2")
        assertEquals(0.0, EquipmentMatcher.tokenSimilarity("table", "tablet", typos = true), "a prefix is never a typo")
        assertEquals(0.0, EquipmentMatcher.tokenSimilarity("chairs", "charts", typos = true), "distance 2")
        assertEquals(0.0, EquipmentMatcher.tokenSimilarity("cane", "cone", typos = true), "too short for a typo")
        assertEquals("cooker", label("Coocker with oven"))
        assertEquals("inverter", label("Power Inventer"))
        assertEquals("towels", label("Towels"))
        assertEquals("electric-fans", label("Fans"))
    }

    @Test
    fun `a name starting with no, without or not never links`() {
        assertNull(label("No bathing platform"))
        assertNull(label("Without generator"))
        assertNull(label("Not available: air conditioning"))
        assertEquals("bathing-platform", label("Bathing platform"))
    }

    @Test
    fun `the earlier word wins, then the more specific key`() {
        assertEquals("fridge", label("Fridge on flybridge"))
        assertEquals("flybridge", label("Flybridge fridge"))
        assertEquals("air-conditioning", label("A/C in salon with shore power"))
        assertEquals("outside-GPS-plotter", label("Chart plotter in cockpit"))
        assertEquals("salon-GPS-plotter", label("Chart plotter"))
        assertEquals("dinghy", label("Dinghy with outboard engine"))
        assertEquals("outside-shower", label("Cockpit/stern, outside shower"))
        assertEquals("shower", label("Shower"))
    }

    @Test
    fun `items the old matcher left unlinked`() {
        assertEquals("wifi", label("Wi-Fi & Internet"))
        assertEquals("wifi", label("Wi-Fi Internet"))
        assertEquals("logge-speed-wind", label("Wind instrument/Anemometer"))
        assertEquals("cooker", label("Stove"))
        assertEquals("BBQ", label("Grill/Barbecue/Plancha"))
        assertEquals("cockpit-cushions", label("Cockpit cushions"))
        assertEquals("depth-sounder", label("Echosounder/Depthsounder"))
        assertEquals("audio-system", label("Stereo"))
        assertEquals("stand-up-paddle", label("SUP"))
    }

    @Test
    fun `a full tie goes to the lower id, whatever order the rows come in`() {
        val first = row(7, "seven", "token-match:kayak")
        val second = row(3, "three", "token-match:kayak")
        assertEquals("three", EquipmentMatcher(listOf(first, second)).best("Kayak")?.labelCode)
        assertEquals("three", EquipmentMatcher(listOf(second, first)).best("Kayak")?.labelCode)
    }

    @Test
    fun `an alias row is never a candidate`() {
        val alias = row(1, "refrigerator", "token-match:refrigerator", mergedInto = 2)
        val canonical = row(2, "fridge", "token-match:fridge")
        assertNull(EquipmentMatcher(listOf(alias, canonical)).best("Refrigerator"))
        assertEquals("fridge", label("Refrigerator"))
        assertEquals("bow-thruster", label("Bow thruster"))
        assertEquals("sun-pads", label("Sundeck cushions"))
    }

    @Test
    fun `empty, blank or longer than 70 characters never links`() {
        assertNull(label(""))
        assertNull(label("   "))
        assertNull(label(null))
        assertNull(label("Fridge " + "x".repeat(70)))
    }
}
