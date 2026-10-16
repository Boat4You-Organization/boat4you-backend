package hr.workspace.boat4you.domains.catalouge.capacity

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The sanitizer gaps of the native note review (15.10.2026): the owner abbreviation "1OWN", a price / surcharge
 * obligation, an engine spec typed into a cabins / berths / heads note and a note naming the yacht itself are hidden -
 * never rewritten, the number still shows - while every other real partner note still passes unchanged.
 */
class CapacityNoteReviewRulesTest {
    private val sanitizer = CapacityFixtures.sanitizer()
    private val capacityMapper = YachtCapacityMapper(sanitizer)

    private fun note(
        text: String,
        dim: CapacityDim = CapacityDim.CABINS,
        vararg names: String?,
    ): String? = sanitizer.capacityNote(text, dim, names.toList())

    @Test
    fun `the owner abbreviation is hidden like owner(s) - the English own stays`() {
        val hide =
            listOf(
                "(1OWN+2D+1BB)",
                "1 OWN + 2 double",
                "(OWN cabin + 3 double)",
                "2own+2",
                "(1Own+3D)",
                // owner written out right after a digit ("owners?" needs a word edge before it)
                "(1OWNER+2D+1BB)",
                "(1Owner + 3 Guest)",
                "2owners",
                // a capital OWN WC with a count before it is the owner's head
                "1 OWN WC + 2 GUEST WC",
                "+ OWN HEAD",
            )
        assertEquals(emptyList(), hide.filter { note(it) != null }, "should be hidden")
        assertEquals(emptyList(), hide.filter { note(it, CapacityDim.HEADS) != null }, "should be hidden as a heads note")
        assertEquals(emptyList(), hide.filter { sanitizer.capacityNote(it) != null }, "also as a label")
        val keep =
            listOf(
                "(4 double cabins, each with own bathroom)",
                "4 cabins (own WC)",
                "+1 down the stairs",
                "(3 double + 1 bunk bed)",
                // the en-suite wording in an all-capitals note
                "4 DOUBLE CABINS WITH OWN BATHROOM",
                "EACH CABIN WITH OWN WC",
                "(4 CABINS, EACH WITH OWN SHOWER/WC)",
            )
        keep.forEach { assertEquals(it, note(it)) }
    }

    @Test
    fun `a price, surcharge or supplement obligation is hidden on every surface`() {
        val hide =
            listOf(
                "Price is for up to 12 pax. For 13 - 18 pax (in shared cabins) surcharge applies.",
                "+2 berths (supplement)",
                "+2 u salonu uz doplatu",
                "+2 (doplata)",
                "(8+2) extra charge for the saloon berths",
                "+2 at extra cost",
                "+2 berths 150€",
                "+2 berths 150 EUR",
                "+2 berths 150EUR",
                "+2 saloon 40 euro per night",
                "+2 \$50",
                "(8+2) fee for extra guests",
                "+2 payable on site",
                "2 extra berths paid separately",
                "+2 (priced on request)",
                "supplemento 2 ospiti",
                "2 suplementos",
                "+2 supplément",
                "+2 Zuschlag",
                // review of the v3 rules, 16.10.2026
                "+2 uz nadoplatu",
                "50 eura po noći",
                "+2 a pagamento",
                "+2 en option payante",
                "+2 kostenpflichtig",
                "+2 gegen Gebühr",
                "+2 (Zuschläge)",
                "+2 supplementi",
                "+2 berths at additional cost",
                "extra bed on request, charged separately",
            )
        assertEquals(emptyList(), hide.filter { note(it, CapacityDim.BERTHS) != null }, "should be hidden")
        assertEquals(emptyList(), hide.filter { sanitizer.capacityNote(it) != null }, "also as a label")
        assertNull(sanitizer.capacityNote("Code zero (extra charge)"), "an unknown sail label too")
        val keep =
            listOf(
                "+2 on request",
                "(incl. saloon bed)",
                "(2 extra single cabins with Wc/Showers in front on request)",
                "(including any crew)",
                "4 + 2 bunks forward",
                "(10+2 dinette +1 skipper) Cat A/B: 13, C16",
                "+2 in Europe-style bunks",
            )
        keep.forEach { assertEquals(it, note(it, CapacityDim.BERTHS)) }
    }

    @Test
    fun `an engine spec in a cabins, berths or heads note is hidden - the engine label itself still shows`() {
        val hide = listOf("Yanmar 20hp", "Volvo Penta D2-40", "20 KS", "(2 x 29 kW)", "4 + 1 (30 hp)", "2x40HP", "Yanmar 3YM30 21 h.p.", "1 Nanni")
        for (dim in CapacityDim.entries) {
            assertEquals(emptyList(), hide.filter { note(it, dim) != null }, "should be hidden as a $dim note")
        }
        assertEquals("Yanmar 20hp", sanitizer.capacityNote("Yanmar 20hp"), "as an engine label it is the engine")
        assertEquals("2x Yanmar 40 hp", sanitizer.capacityNote("2x Yanmar 40 hp"))
        // capacity notes that only look close: design category, "motor yacht", port side, convertible
        val keep =
            listOf(
                "(10+2 dinette) Cat.A10 people,B11,C12",
                "Motor Yacht Calm Down has 6 Cabins; 3 Master, 2 Double, 1 Twin all en-suite.",
                "+1 cabin PS",
                "(4+2 cv)",
                "4 + 1 man crew",
                "3 Showers including the one on the swimming platform",
                // Vetus also makes marine toilets
                "Vetus electric WC",
                "2 electric toilets (Vetus)",
            )
        keep.forEach { assertEquals(it, note(it, CapacityDim.HEADS)) }
    }

    @Test
    fun `a note naming the yacht itself is hidden - public or partner name, folded, whole words`() {
        val dea = "Dea Del Mare has 5 Cabins; 2 Master, 2 Double, 1 Twin all en suite."
        val calm = "Motor Yacht Calm Down has 6 Cabins; 3 Master, 2 Double, 1 Twin all en-suite."
        assertNull(note(dea, CapacityDim.CABINS, "Dea Del Mare", null))
        assertNull(note(calm, CapacityDim.CABINS, "CALM DOWN", null), "case does not matter")
        assertNull(note(dea, CapacityDim.CABINS, "Bella", "Dea Del Mare"), "the raw partner name counts too")
        assertNull(note("Đurđica: 4+1", CapacityDim.CABINS, "Durdica"), "diacritics fold")
        // another boat's note, a name inside a word, capacity words and numbers as a name: kept
        assertEquals(dea, note(dea, CapacityDim.CABINS, "Bella", null))
        assertEquals(dea, note(dea, CapacityDim.CABINS), "no name known")
        assertEquals("Ideal for 4 + 1", note("Ideal for 4 + 1", CapacityDim.BERTHS, "Dea"))
        assertEquals("(4+1 master cabin)", note("(4+1 master cabin)", CapacityDim.CABINS, "Master"))
        assertEquals("(4 + 1 twin cabin)", note("(4 + 1 twin cabin)", CapacityDim.CABINS, "Twin Cabin"))
        assertEquals("(10+2 dinette +1 skipper) Cat A: 12", note("(10+2 dinette +1 skipper) Cat A: 12", CapacityDim.BERTHS, "12"))
        assertEquals("(4+1) No", note("(4+1) No", CapacityDim.CABINS, "No"), "shorter than 3 letters")
    }

    @Test
    fun `the mapper hides them per figure - the value still shows, the rig keeps its engine label`() {
        val columns =
            CapacityColumns(
                cabins = 5,
                cabinsNote = "Dea Del Mare has 5 Cabins; 2 Master, 2 Double, 1 Twin all en suite.",
                berths = 12,
                berthsNote = "Price is for up to 12 pax. For 13 - 18 pax (in shared cabins) surcharge applies.",
                wc = 2,
                wcNote = "Yanmar 20hp",
                engineLabel = "Yanmar 20hp",
                yachtName = "Bella",
                partnerYachtName = "Dea Del Mare",
            )
        for (mode in YachtCapacityMapper.Mode.entries) {
            val capacity = capacityMapper.capacity(columns, mode)
            assertEquals(5.toShort(), capacity.cabins!!.value)
            assertNull(capacity.cabins!!.note, "$mode: names the boat (partner name)")
            assertEquals(12.toShort(), capacity.berths!!.value)
            assertNull(capacity.berths!!.note, "$mode: surcharge")
            assertEquals(2.toShort(), capacity.heads!!.value)
            assertNull(capacity.heads!!.note, "$mode: engine in the WC note")
        }
        assertEquals("Yanmar 20hp", capacityMapper.rig(columns).engine?.label)
        val other = capacityMapper.capacity(columns.copy(partnerYachtName = null), YachtCapacityMapper.Mode.FULL)
        assertNotNull(other.cabins!!.note, "another boat's name does not hide it")
    }

    @Test
    fun `all 71 sample notes still pass unchanged in every figure, with the reference boats' names`() {
        val notes = CapacityFixtures.json("partner_texts.json").path("notes").map { it.asText() }
        val names = listOf("Dione II", "Jangada", "PNOE", "Marea", "Bavaria 46")
        for (dim in CapacityDim.entries) {
            assertEquals(emptyList(), notes.filter { sanitizer.capacityNote(it, dim, names) != it }, "$dim notes hidden or rewritten")
        }
    }

    /**
     * Every prod note of the v2/v3 table (1,660 notes, infra/capacity-contract-6-10/capacityNotes.json) in each figure it
     * was seen in: the 22 the table marks hidden stay hidden, and of the rest exactly the three the review found are
     * newly hidden (the two notes naming their boat need the boat's name, tested above). Skipped when infra is not
     * checked out next to the backend.
     */
    @Test
    fun `the prod note table - exactly the review's three notes are newly hidden`() {
        val table =
            listOf("../../infra", "../../../infra")
                .map { File(it, "capacity-contract-6-10/capacityNotes.json") }
                .firstOrNull { it.isFile }
        assumeTrue(table != null, "infra/capacity-contract-6-10 not checked out next to the backend")
        val notes = CapacityFixtures.mapper.readTree(table!!).path("notes")
        val newlyHidden = mutableSetOf<String>()
        val stillShown = mutableListOf<String>()
        var checked = 0
        notes.fields().forEach { (key, entry) ->
            val hidden = !entry.path("hidden").isMissingNode && !entry.path("hidden").isNull
            entry.path("dims").map { CapacityDim.valueOf(it.asText().uppercase()) }.forEach { dim ->
                checked++
                val shown = sanitizer.capacityNote(key, dim, emptyList())
                when {
                    hidden && shown != null -> stillShown += "$dim $key"
                    !hidden && shown == null -> newlyHidden += key
                    !hidden -> assertEquals(key, shown, "rewritten")
                }
            }
        }
        assertEquals(true, checked > 1800, "note x figure rows: $checked")
        assertEquals(emptyList(), stillShown, "the table's hidden notes must stay hidden")
        // the table's scan ran the operator list only; the agency names (here the seeds) already hid this one before
        val byAgencyName = newlyHidden.filter { CapacityFixtures.nameMatcher.matches(it) }.toSet()
        assertEquals(setOf("(12+2 optional professional Greek crew)"), byAgencyName, "the brand \"Greek\" of the agency Greek Yachts")
        assertEquals(
            setOf("(1OWN+2D+1BB)", "Price is for up to 12 pax. For 13 - 18 pax (in shared cabins) surcharge applies.", "Yanmar 20hp"),
            newlyHidden - byAgencyName,
        )
    }
}
