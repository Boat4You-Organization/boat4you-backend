package hr.workspace.boat4you.domains.catalouge.capacity

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Strict capacity-note parser (capacity contract v1, 5.2) against the reviewed table of every note x figure x partner
 * number in the 896-yacht MMK sample (expectations/parser.json, 89 combinations), plus the contract's edge cases.
 */
class CapacityNoteParserTest {
    private fun dim(name: String) =
        when (name) {
            "cabins" -> CapacityDim.CABINS
            "berths" -> CapacityDim.BERTHS
            "heads" -> CapacityDim.HEADS
            else -> error(name)
        }

    @Test
    fun `every note in the MMK sample parses as reviewed (expectations parser json)`() {
        val table = CapacityFixtures.json("parser.json")
        assertEquals(89, table.size())
        val mismatches = mutableListOf<String>()
        val parsed = mutableMapOf("cabins" to 0, "berths" to 0, "heads" to 0)
        table.fields().forEach { (key, expected) ->
            val (dimName, value, note) = key.split('|', limit = 3)
            val actual = CapacityNoteParser.parse(value.toInt(), note, dim(dimName))
            val expectedSplit =
                if (expected.isNull) {
                    null
                } else {
                    CapacitySplit(
                        guests = expected.path("guests").takeIf { it.isNumber }?.asInt(),
                        inCabins = expected.path("inCabins").takeIf { it.isNumber }?.asInt(),
                        saloon = expected.path("saloon").takeIf { it.isNumber }?.asInt(),
                        crew = expected.path("crew").takeIf { it.isNumber }?.asInt(),
                        skipper = expected.path("skipper").takeIf { it.isNumber }?.asInt(),
                    )
                }
            if (actual != expectedSplit) mismatches += "$key: expected $expectedSplit, got $actual"
            if (actual != null) parsed[dimName] = parsed.getValue(dimName) + 1
        }
        assertEquals(emptyList(), mismatches)
        // distinct note x figure x number combinations that parse (the contract's 33 / 55 / 1 count yachts)
        assertEquals(mapOf("cabins" to 5, "berths" to 3, "heads" to 1), parsed)
    }

    @Test
    fun `contract examples`() {
        assertEquals(CapacitySplit(guests = 12, crew = 1), CapacityNoteParser.parse(13, "(12 pax + 1 Crew)", CapacityDim.BERTHS))
        assertEquals(CapacitySplit(guests = 8, saloon = 2), CapacityNoteParser.parse(10, "(8+2 saloon)", CapacityDim.BERTHS))
        assertEquals(CapacitySplit(guests = 8, crew = 2), CapacityNoteParser.parse(10, "(8guests + 2crew)", CapacityDim.BERTHS))
        assertEquals(CapacitySplit(guests = 4, skipper = 1), CapacityNoteParser.parse(5, "4+1 skipper", CapacityDim.CABINS))
        assertEquals(CapacitySplit(guests = 5, crew = 1), CapacityNoteParser.parse(6, "(5+1 for the crew)", CapacityDim.HEADS))
        assertNull(CapacityNoteParser.parse(10, "8+2", CapacityDim.BERTHS), "unlabelled extra (Jangada: convertible saloon table)")
        assertNull(
            CapacityNoteParser.parse(6, "(5 double +1 for the hostess + 1 bow/skippers cabin)", CapacityDim.CABINS),
            "adds up to 7, not 6",
        )
        assertNull(CapacityNoteParser.parse(4, "+1 skipper", CapacityDim.CABINS), "leading + : the number may exclude the extra")
        assertNull(CapacityNoteParser.parse(4, "(4/5)", CapacityDim.HEADS))
        assertNull(CapacityNoteParser.parse(4, "(4+1 in one of the crew cabins)", CapacityDim.HEADS))
        assertNull(CapacityNoteParser.parse(4, "+1 skipper wc", CapacityDim.CABINS))
    }

    @Test
    fun `edge cases`() {
        assertNull(CapacityNoteParser.parse(null, "(4+1 skipper)", CapacityDim.CABINS))
        assertNull(CapacityNoteParser.parse(0, "(4+1 skipper)", CapacityDim.CABINS))
        assertNull(CapacityNoteParser.parse(5, null, CapacityDim.CABINS))
        assertNull(CapacityNoteParser.parse(5, "", CapacityDim.CABINS))
        assertEquals(CapacitySplit(guests = 4, skipper = 1), CapacityNoteParser.parse(5, "(4 + 1 skipper cabin)", CapacityDim.CABINS))
        assertNull(CapacityNoteParser.parse(5, "(4 + 1 skipper berth)", CapacityDim.CABINS), "berth noun on cabins")
        assertEquals(CapacitySplit(guests = 6, skipper = 1), CapacityNoteParser.parse(7, "6 + 1 skipper wc", CapacityDim.HEADS))
        assertNull(CapacityNoteParser.parse(10, "(8 + 2 saloon)", CapacityDim.CABINS), "saloon only for berths")
        assertEquals(
            CapacitySplit(guests = 8, saloon = 2, crew = 1),
            CapacityNoteParser.parse(11, "(8 pax + 2 saloon + 1 crew)", CapacityDim.BERTHS),
        )
        assertNull(CapacityNoteParser.parse(11, "(8 double + 2 saloon + 1 crew)", CapacityDim.BERTHS), "first part must be guests")
        assertNull(CapacityNoteParser.parse(9, "(8 + 1)", CapacityDim.BERTHS))
        assertNull(CapacityNoteParser.parse(9, "plus 1 crew", CapacityDim.BERTHS))
        assertNull(CapacityNoteParser.parse(9, "– 8 + 1 crew", CapacityDim.BERTHS))
        assertNull(CapacityNoteParser.parse(9, "(8+1 crew) (+1)", CapacityDim.BERTHS))
        assertEquals(CapacitySplit(guests = 12, crew = 2), CapacityNoteParser.parse(14, "12 + 1 crew + 1 hostess", CapacityDim.BERTHS))
        assertEquals(CapacitySplit(guests = 12, crew = 1), CapacityNoteParser.parse(13, "  (12 pax + 1 Crew) ", CapacityDim.BERTHS))
    }

    @Test
    fun `NauSys berths split holds only when the parts add up and at least two are non-zero`() {
        assertEquals(CapacitySplit(inCabins = 8, saloon = 2, crew = 2), CapacityNoteParser.nausysBerthsSplit(12, 8, 2, 2))
        assertNull(CapacityNoteParser.nausysBerthsSplit(12, 12, 0, 0), "single part = the number itself")
        assertNull(CapacityNoteParser.nausysBerthsSplit(12, 8, 2, 1), "identity broken -> no split")
        assertNull(CapacityNoteParser.nausysBerthsSplit(12, null, 2, 2))
        assertEquals(CapacitySplit(inCabins = 6, saloon = 2), CapacityNoteParser.nausysBerthsSplit(8, 6, 2, null))
        assertNull(CapacityNoteParser.nausysBerthsSplit(0, 0, 0, 0))
    }
}
