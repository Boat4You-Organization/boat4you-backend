package hr.workspace.boat4you.domains.catalouge.capacity

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.nausys.config.nauSysObjectMapper
import org.junit.jupiter.api.Test
import org.openapitools.client.nausys.model.RestYacht
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole backend chain on the contract's 7 reference boats (capacity contract v1, section 8): partner payload (as the
 * MMK / NauSys clients deserialize it) -> PartnerCapacitySync -> yacht columns -> YachtCapacityMapper -> JSON, compared
 * with the detail `capacity` / `rig` and the search `capacity` blocks the frontends' snapshot tests render
 * (expectations/reference_boats.json). Payloads are trimmed to the capacity fields (no company or crew names).
 */
class PartnerCapacityReferenceBoatsTest {
    private val mapper = CapacityFixtures.mapper
    private val nausysMapper = nauSysObjectMapper()
    private val capacityMapper = YachtCapacityMapper(CapacityFixtures.sanitizer())
    private val boats: JsonNode = CapacityFixtures.json("reference_boats.json")

    /** /agency|external|partner|company|source|mmk|nausys|operator/i - the sisters' withoutPartnerIds drops such keys. */
    private val partnerIdKey = Regex("agency|external|partner|company|source|mmk|nausys|operator", RegexOption.IGNORE_CASE)

    private fun yacht(key: String): Yacht {
        val boat = boats.path(key)
        val yacht = Yacht()
        when (boat.path("source").asText()) {
            "mmk" ->
                PartnerCapacitySync.applyMmk(
                    yacht,
                    mapper.treeToValue(boat.path("payload"), org.openapitools.client.mmk.model.Yacht::class.java),
                )
            "ns" -> PartnerCapacitySync.applyNausys(yacht, nausysMapper.treeToValue(boat.path("payload"), RestYacht::class.java))
            else -> error(key)
        }
        return yacht
    }

    private fun tree(value: Any?): JsonNode = mapper.readTree(mapper.writeValueAsString(value))

    private fun keys(
        node: JsonNode,
        out: MutableSet<String> = mutableSetOf(),
    ): Set<String> {
        node.fields().forEach { (k, v) ->
            out += k
            keys(v, out)
        }
        return out
    }

    @Test
    fun `payload to detail capacity, rig and search capacity, for all 7 reference boats`() {
        val mismatches = mutableListOf<String>()
        boats.fieldNames().forEach { key ->
            val columns = CapacityColumns.of(yacht(key))
            val expected = boats.path(key)
            val detailCapacity = tree(capacityMapper.capacity(columns, YachtCapacityMapper.Mode.FULL))
            val rig = tree(capacityMapper.rig(columns))
            val search = tree(capacityMapper.capacity(columns, YachtCapacityMapper.Mode.BRIEF))
            if (detailCapacity != expected.path("detail").path("capacity")) mismatches += "$key detail.capacity: $detailCapacity"
            if (rig != expected.path("detail").path("rig")) mismatches += "$key detail.rig: $rig"
            if (search != expected.path("search")) mismatches += "$key search: $search"
            keys(detailCapacity).plus(keys(rig)).plus(keys(search)).filter { partnerIdKey.containsMatchIn(it) }.forEach {
                mismatches += "$key: public key '$it' matches the partner-id pattern"
            }
        }
        assertEquals(emptyList(), mismatches)
        assertEquals(7, boats.size())
    }

    @Test
    fun `sync columns - crew WC from wcCrew, sail filter enum corrected, engine filter value, notes and remark stored`() {
        val dione = yacht("dione")
        assertEquals(SailTypeEnum.CLASSIC_SAIL, dione.mainsailType, "Full batten was ROLLING_SAIL")
        assertEquals(SailTypeEnum.ROLLING_SAIL, dione.genoaType)
        assertEquals(120.toShort(), dione.enginePower)
        assertEquals("2x60HP", dione.engineLabel)
        assertEquals("(12 pax + 1 Crew)", dione.berthsNote)
        assertEquals("Full batten", dione.mainsailLabel)
        assertEquals(1.toShort(), dione.crewNumber)
        assertNull(dione.crewWc)
        assertNull(dione.cabinBerths)
        assertTrue(dione.internalRemark!!.startsWith("CREWED |"), "MMK comment stored for the admin")

        val jangadaNs = yacht("jangadaNs")
        assertEquals(0.toShort(), jangadaNs.crewWc, "crew_wc from wcCrew (bug fix), not wc")
        assertEquals(2.toShort(), jangadaNs.wc)
        assertEquals(8.toShort(), jangadaNs.cabinBerths)
        assertEquals(2.toShort(), jangadaNs.salonBerths)
        assertEquals(0.toShort(), jangadaNs.showers, "as sent; 0 hidden only at read time")
        assertEquals("full batten", jangadaNs.mainsailLabel)
        assertEquals("self tacking jib", jangadaNs.genoaLabel)
        assertEquals(SailTypeEnum.CLASSIC_SAIL, jangadaNs.mainsailType, "NauSys 3 full batten was ROLLING_SAIL")
        assertEquals(60.toShort(), jangadaNs.enginePower)
        assertEquals(0, BigDecimal("30").compareTo(jangadaNs.enginePowerEach))
        assertNull(jangadaNs.cabinsNote)

        val corali = yacht("corali")
        assertEquals(2.toShort(), corali.crewWc, "was 4 (= wc)")
        assertEquals(SailTypeEnum.ROLLING_SAIL, corali.genoaType)

        val marea = yacht("marea")
        assertEquals(10.toShort(), marea.recommendedPersons)
        assertNull(marea.genoaLabel, "no genoaTypeId")
    }

    @Test
    fun `MMK crew number resets when the partner empties the crew list, and a cleared note is cleared`() {
        val payload = boats.path("dione").path("payload").deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        val yacht = yacht("dione")
        payload.putArray("crew")
        payload.putNull("berthsNote")
        payload.put("engine", "2x 45")
        PartnerCapacitySync.applyMmk(yacht, mapper.treeToValue(payload, org.openapitools.client.mmk.model.Yacht::class.java))
        assertNull(yacht.crewNumber)
        assertNull(yacht.berthsNote)
        assertNull(yacht.enginePower, "no unit: no filter value (was 90)")
        assertEquals("2x 45", yacht.engineLabel, "stored as sent")
        assertNull(capacityMapper.rig(CapacityColumns.of(yacht)).engine, "and not shown")
    }

    @Test
    fun `the internal remark never reaches the public blocks, only the admin broker notes`() {
        val columns = CapacityColumns.of(yacht("dione"))
        val public =
            mapper.writeValueAsString(capacityMapper.capacity(columns, YachtCapacityMapper.Mode.FULL)) +
                mapper.writeValueAsString(capacityMapper.capacity(columns, YachtCapacityMapper.Mode.BRIEF)) +
                mapper.writeValueAsString(capacityMapper.rig(columns))
        assertFalse(public.contains("CREWED"), public)
        val broker = tree(capacityMapper.brokerNotes(columns))
        assertTrue(broker.path("remark").asText().startsWith("CREWED |"))
        assertEquals("(12 pax + 1 Crew)", broker.path("berthsNote").asText())
        assertEquals(setOf("cabinsNote", "berthsNote", "headsNote", "remark"), keys(broker))
        keys(broker).forEach { assertFalse(partnerIdKey.containsMatchIn(it), it) }
    }

    @Test
    fun `a note naming an operator is hidden - the number still shows, without a split`() {
        val columns = CapacityColumns(berths = 13, berthsNote = "(12 pax + 1 Crew) Sunsail", cabins = 4, cabinsNote = "4 +2")
        val capacity = capacityMapper.capacity(columns, YachtCapacityMapper.Mode.FULL)
        assertEquals(13.toShort(), capacity.berths!!.value)
        assertNull(capacity.berths!!.note)
        assertNull(capacity.berths!!.split, "a hidden note gives no split")
        assertEquals("4 +2", capacity.cabins!!.note)
        val engine = capacityMapper.rig(CapacityColumns(engineLabel = "2x60HP Sunsail")).engine
        assertNull(engine, "an engine label naming an operator is hidden")
        val sail = capacityMapper.rig(CapacityColumns(mainsailLabel = "Code zero", genoaLabel = "None")).mainsail
        assertEquals("Code zero", sail!!.label, "unknown label: shown as sent")
        assertNull(sail.kind)
        assertNull(capacityMapper.rig(CapacityColumns(genoaLabel = "None")).headsail, "the partner says none")
    }
}
