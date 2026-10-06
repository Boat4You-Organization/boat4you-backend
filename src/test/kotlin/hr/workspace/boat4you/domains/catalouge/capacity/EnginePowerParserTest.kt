package hr.workspace.boat4you.domains.catalouge.capacity

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine power for the filter + the "show the MMK engine label" gate (capacity contract v1, 5.3): every distinct engine
 * string of the 896-yacht MMK sample (130, reviewed one by one in expectations/engine_power.json).
 */
class EnginePowerParserTest {
    @Test
    fun `every distinct MMK engine string gives the reviewed filter value and label gate`() {
        val table = CapacityFixtures.json("engine_power.json")
        assertEquals(130, table.size())
        val mismatches = mutableListOf<String>()
        var yachtsWithoutValue = 0
        var yachts = 0
        table.fields().forEach { (raw, expected) ->
            val hp = EnginePowerParser.parseHp(raw)
            val expectedHp = expected.path("hp").takeIf { it.isNumber }?.asInt()
            if (hp != expectedHp) mismatches += "'$raw': hp expected $expectedHp, got $hp"
            val shown = EnginePowerParser.hasUnit(raw)
            if (shown != expected.path("shownLabel").asBoolean()) mismatches += "'$raw': label shown expected ${!shown}"
            yachts += expected.path("yachts").asInt()
            if (hp == null) yachtsWithoutValue += expected.path("yachts").asInt()
        }
        assertEquals(emptyList(), mismatches)
        assertEquals(896, yachts)
        assertEquals(75, yachtsWithoutValue, "35 empty strings + 40 strings without a unit")
    }

    @Test
    fun `contract fixes and ambiguous strings`() {
        assertEquals(40, EnginePowerParser.parseHp("Volvo MD 22 Saildrive 40 h.p."), "was 880")
        assertEquals(45, EnginePowerParser.parseHp("33.12Kw"), "was 396")
        assertEquals(28, EnginePowerParser.parseHp("28.40 BHp"), "was 1120")
        assertEquals(80, EnginePowerParser.parseHp("Volvo D2-40, 2x40 HP"), "was null")
        assertEquals(640, EnginePowerParser.parseHp("2xYanmar 320 HP"))
        assertEquals(75, EnginePowerParser.parseHp("Volvo 75 ΗΡ"), "Greek Eta + Rho")
        assertEquals(120, EnginePowerParser.parseHp("2x60HP"))
        listOf("27.3", "2x 45", "2*57", "Volvo 55", "Volvo Penta D2-75", "Yanmar", "NANNI", "", null).forEach {
            assertNull(EnginePowerParser.parseHp(it), "no unit: '$it'")
            assertFalse(EnginePowerParser.hasUnit(it), "label hidden: '$it'")
        }
        assertNull(EnginePowerParser.parseHp("2x40 HP / 1x75 HP"), "two different powers")
        assertTrue(EnginePowerParser.hasUnit("2x40 HP / 1x75 HP"), "but the label carries a unit")
        assertNull(EnginePowerParser.parseHp("5x40 HP"), "count outside 1-4")
        assertNull(EnginePowerParser.parseHp("4000 hp"), "per-engine power outside 1-3000")
        // the old extractAndMultiplyNumbers cases still hold where they carry a unit
        assertEquals(880, EnginePowerParser.parseHp("2x440 Hp Volvo"))
        assertEquals(880, EnginePowerParser.parseHp("2x 440 Hp Volvo"))
        assertEquals(880, EnginePowerParser.parseHp("2x Volvo 440 hp"))
        assertEquals(880, EnginePowerParser.parseHp("880 Hp Volvo"))
        assertEquals(400, EnginePowerParser.parseHp("4 x 100 Hp"))
    }

    @Test
    fun `NauSys engines x power per engine, half up, null without power`() {
        assertEquals(230, EnginePowerParser.nausysTotalHp(2, BigDecimal("115.0")))
        assertEquals(115, EnginePowerParser.nausysTotalHp(2, BigDecimal("57.25")), "rounded half up, not truncated per engine")
        assertEquals(30, EnginePowerParser.nausysTotalHp(null, BigDecimal("30")), "no count = one engine")
        assertEquals(30, EnginePowerParser.nausysTotalHp(0, BigDecimal("30")))
        assertNull(EnginePowerParser.nausysTotalHp(2, null))
        assertNull(EnginePowerParser.nausysTotalHp(2, BigDecimal.ZERO))
        assertNull(EnginePowerParser.nausysTotalHp(2, BigDecimal("-1")))
        assertNull(EnginePowerParser.nausysTotalHp(2, BigDecimal("1E+12")), "beyond any boat: not a filter value")
    }
}
