package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.core.io.ClassPathResource
import java.io.File
import java.util.concurrent.Executor
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
    fun `brand alone, short and non-Latin agency names, compatibility characters - hidden`() {
        val s = CapacityFixtures.sanitizer(CapacityFixtures.agencySeedNames + listOf("TYC", "NCC", "Γιώτινγκ Ελλάς", "Яхтинг Адриатика"))
        val hide =
            listOf(
                // operators.txt "Navigare Yachting" / "Pitter Yachtcharter": the brand alone
                "(4+1) Navigare",
                "(4+1) Pitter",
                // agency names of 3 letters
                "4+1 TYC skipper",
                "(4+1) NCC",
                // Greek / Cyrillic names (they folded to nothing before)
                "(4+1) Γιώτινγκ Ελλάς",
                "4+2 яхтинг адриатика",
                // fullwidth / compatibility forms of a domain, a phone word and a name
                "example．com",
                "call ０９１ １２３ ４５６７",
                "(4+1) Ｎａｖｉｇａｒｅ",
            )
        assertEquals(emptyList(), hide.filter { s.capacityNote(it) != null }, "should be hidden")
        // capacity words never become a name: "Seven Charter", "Master Yachting", "Starboard Charter" are operators
        val keep = listOf("seven berths", "(4+1 master cabin)", "+1 starboard bow cabin", "4 + 2 bunks forward", "2x Yanmar 40 hp")
        // ...but an operator's brand stays hidden even where it is also a word: "MainSail Yachting", "Genoa Sailing"
        assertNull(s.capacityNote("4+1 MainSail"))
        assertNull(s.capacityNote("(4+2) Genoa"))
        keep.forEach { assertEquals(it, s.capacityNote(it)) }
        // an allow-listed name gives no short form either ("Signature Sailing" is allow-listed in operators.txt)
        assertEquals("(4+2) Signature", s.capacityNote("(4+2) Signature"))
    }

    @Test
    fun `agency names are read at startup, then re-read in the background after an hour - never on the caller's thread`() {
        val agencies = mock(AgencyRepository::class.java)
        `when`(agencies.findAllNames()).thenReturn(listOf("Adriatic Blue Waters d.o.o."))
        val s = PartnerTextSanitizer(agencies)
        var now = 1_000_000L
        s.nowMillis = { now }
        val queued = mutableListOf<Runnable>()
        s.refreshExecutor = Executor { queued += it }
        s.loadAgencyNames()

        assertNull(s.capacityNote("4+1 Adriatic Blue Waters"), "the agency's short form (legal form dropped) is matched")
        assertEquals("4+1 Kestrel Bay", s.capacityNote("4+1 Kestrel Bay"))
        verify(agencies, times(1)).findAllNames()

        // MMK auto-creates an agency: still the cached list within the hour
        `when`(agencies.findAllNames()).thenReturn(listOf("Adriatic Blue Waters d.o.o.", "Kestrel Bay Ltd"))
        now += 59 * 60 * 1000L
        assertEquals("4+1 Kestrel Bay", s.capacityNote("4+1 Kestrel Bay"))
        assertTrue(queued.isEmpty())

        // after the hour: the caller keeps the old list and schedules ONE background read, the caller's thread reads nothing
        now += 2 * 60 * 1000L
        assertEquals("4+1 Kestrel Bay", s.capacityNote("4+1 Kestrel Bay"))
        assertEquals("4+1 Kestrel Bay", s.capacityNote("4+1 Kestrel Bay"))
        assertEquals(1, queued.size, "a single refresh in flight")
        verify(agencies, times(1)).findAllNames()
        queued.removeAt(0).run()
        verify(agencies, times(2)).findAllNames()
        assertNull(s.capacityNote("4+1 Kestrel Bay"))

        // the agency table cannot be read: the last list stays, nothing fails, the read is retried after a minute
        `when`(agencies.findAllNames()).thenThrow(IllegalStateException("db down"))
        now += 61 * 60 * 1000L
        assertNull(s.capacityNote("4+1 Kestrel Bay"))
        queued.removeAt(0).run()
        assertNull(s.capacityNote("4+1 Kestrel Bay"), "last good list kept")
        assertNull(s.capacityNote("(4+1) Sunsail"), "operators.txt still applies")
        assertEquals("(4+1)", s.capacityNote("(4+1)"))
        assertTrue(queued.isEmpty(), "no retry within the minute")
        now += 61 * 1000L
        s.capacityNote("(4+1)")
        assertEquals(1, queued.size, "retried after a minute")
    }

    @Test
    fun `a failing agency table at startup leaves operators txt in force`() {
        val agencies = mock(AgencyRepository::class.java)
        `when`(agencies.findAllNames()).thenThrow(IllegalStateException("db down"))
        val s = PartnerTextSanitizer(agencies)
        s.refreshExecutor = Executor { }
        s.loadAgencyNames()
        assertNull(s.capacityNote("(4+1) Sunsail"))
        assertEquals("4+1 Kestrel Bay", s.capacityNote("4+1 Kestrel Bay"))
    }

    @Test
    fun `the shipped operators txt is the infra copy`() {
        val infra = File("../../infra/deploy-scripts/operators.txt")
        assumeTrue(infra.isFile, "infra/deploy-scripts not checked out next to the backend")
        val shipped = ClassPathResource(PartnerTextSanitizer.OPERATORS_RESOURCE).inputStream.bufferedReader().use { it.readText() }
        assertEquals(infra.readText(), shipped, "copy infra/deploy-scripts/operators.txt to src/main/resources/partner/operators.txt")
    }
}
