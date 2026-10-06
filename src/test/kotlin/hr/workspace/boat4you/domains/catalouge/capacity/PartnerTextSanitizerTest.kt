package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one capacity-note gate (capacity contract v1, section 6): with the real operators.txt (shipped as a resource) and
 * the 1,559 seed agency names, every real partner note, unit-bearing engine label and sail label of the 896-yacht MMK
 * sample passes UNCHANGED, while operator / agency names, contact data, partner prose, internal remarks, markup and
 * over-long text are hidden - never rewritten.
 */
class PartnerTextSanitizerTest {
    private val sanitizer = CapacityFixtures.sanitizer()

    @Test
    fun `the name lists are the real ones`() {
        assertTrue(CapacityFixtures.operators.first.size > 1000, "operators.txt names: ${CapacityFixtures.operators.first.size}")
        assertTrue(CapacityFixtures.operators.second.size > 40, "operators.txt allow-list: ${CapacityFixtures.operators.second.size}")
        assertTrue(CapacityFixtures.agencySeedNames.size > 1500, "agency seed names: ${CapacityFixtures.agencySeedNames.size}")
        assertTrue(CapacityFixtures.nameMatcher.size > 1400, "folded names: ${CapacityFixtures.nameMatcher.size}")
    }

    @Test
    fun `all 71 sample notes, all unit-bearing engine labels and all sail labels pass unchanged`() {
        val texts = CapacityFixtures.json("partner_texts.json")
        val notes = texts.path("notes").map { it.asText() }
        assertEquals(71, notes.size)
        assertEquals(emptyList(), notes.filter { sanitizer.capacityNote(it) != it }, "notes hidden or rewritten")

        val engines =
            CapacityFixtures
                .json("engine_power.json")
                .fieldNames()
                .asSequence()
                .mapNotNull { CapacityText.normalizeNote(it) }
                .filter { EnginePowerParser.hasUnit(it) }
                .toList()
        assertEquals(121, engines.size)
        assertEquals(emptyList(), engines.filter { sanitizer.capacityNote(it) != it }, "engine labels hidden")

        val sails = texts.path("sailLabels").map { it.asText() }
        assertEquals(emptyList(), sails.filter { sanitizer.capacityNote(it) != it }, "sail labels hidden")
    }

    @Test
    fun `hides operator, agency, contact, prose, internal, markup and length - never rewrites`() {
        val hide =
            listOf(
                "(4+1 Sunsail skipper)",
                "SUNSAIL 4+1",
                "(8+2) The Moorings",
                "(4+2) Navigare Yachting",
                "(8+2 saloon) call +385 91 123 4567",
                "tel: 0911234567",
                "info@abavela.com",
                "see www.example.com",
                "example.hr 4+2",
                "(to be confirmed)",
                "4+1 (TBC)",
                "our skipper cabin",
                "payable at the base",
                "owner cabin + 2",
                "internal: 4+2",
                "net price for agents",
                "ask the base!!",
                "<b>4+2</b>",
                "4+2 {x}",
                "null",
                "x".repeat(121),
                "12345678901 crew",
                "Charter company cabin",
                "MMK note 4+1",
                "4+2​",
            )
        assertEquals(emptyList(), hide.filter { sanitizer.capacityNote(it) != null }, "should be hidden")

        val keep = listOf("+2", "(4/5)", "/8", "- 4 double ensuite cabins +2 at forepeak cabins - no toilet", "(3+1 )", "+ 1", "Reccomended Guests Number : 6")
        keep.forEach { assertEquals(it, sanitizer.capacityNote(it)) }
        assertEquals("(8+2) saloon", sanitizer.capacityNote("  (8+2)  saloon "), "normalized only")
        assertNull(sanitizer.capacityNote(null))
        assertNull(sanitizer.capacityNote("   "))
        // the sisters' safePartnerText would hide these (no letter) or strip the "+": kept verbatim here
        listOf("+2", "8+2", "(6+2)", "+ 1", "/5").forEach { assertEquals(it, CapacityNoteRules.capacityNote(it) { false }) }
    }

    @Test
    fun `agency names come from the agency table, re-read after an hour, and a failed read keeps the last list`() {
        val agencies = mock(AgencyRepository::class.java)
        `when`(agencies.findAllNames()).thenReturn(listOf("Adriatic Blue Waters d.o.o."))
        val s = PartnerTextSanitizer(agencies)
        var now = 1_000_000L
        s.nowMillis = { now }

        assertNull(s.capacityNote("4+1 Adriatic Blue Waters"), "the agency's short form (legal form dropped) is matched")
        assertEquals("4+1 Ionian Pearl", s.capacityNote("4+1 Ionian Pearl"))

        // MMK auto-creates an agency: hidden once the hour is over
        `when`(agencies.findAllNames()).thenReturn(listOf("Adriatic Blue Waters d.o.o.", "Ionian Pearl Ltd"))
        now += 59 * 60 * 1000L
        assertEquals("4+1 Ionian Pearl", s.capacityNote("4+1 Ionian Pearl"), "still the cached list")
        now += 2 * 60 * 1000L
        assertNull(s.capacityNote("4+1 Ionian Pearl"))
        verify(agencies, times(2)).findAllNames()

        // the agency table cannot be read: the last list stays, the page does not fail
        `when`(agencies.findAllNames()).thenThrow(IllegalStateException("db down"))
        now += 61 * 60 * 1000L
        assertNull(s.capacityNote("4+1 Ionian Pearl"))
        assertNull(s.capacityNote("(4+1) Sunsail"), "operators.txt still applies")
        assertEquals("(4+1)", s.capacityNote("(4+1)"))
    }
}
