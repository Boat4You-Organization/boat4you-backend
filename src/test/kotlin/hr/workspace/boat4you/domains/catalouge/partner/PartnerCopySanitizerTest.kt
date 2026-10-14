package hr.workspace.boat4you.domains.catalouge.partner

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R01 partner copy (owner rule 14.10.2026) on the real audit strings of 29.9.2026 and the rehearsal copy: curated
 * partner names -> Boat4You, internal notes dropped, premises neutral, disclaimers dropped, contacts dropped, and the
 * ordinary words / numbers that only look like a partner or a phone number left byte-identical.
 */
class PartnerCopySanitizerTest {
    private val s = PartnerCopySanitizer

    // ---- names -------------------------------------------------------------------------------------------------------

    @Test
    fun `extra names - confirm notes, internal notes and partner product names`() {
        assertEquals(
            "Navbow Seascooter (+ deposit 600€ - to be confirmed by Boat4You)",
            s.extraName("Navbow Seascooter (+ deposit 600€ - to be confirmed by NSS)"),
        )
        assertEquals("Early Check-in (14:00pm - to be confirmed by Boat4You)", s.extraName("Early Check-in (14:00pm - to be reconfirmed by NSS office)"))
        assertEquals(
            "Embarkation/Disembarkation Martinique (to be confirmed by Boat4You)",
            s.extraName("Embarkation/Disembarkation Martinique (to be reconfirmed by NSS office)"),
        )
        assertEquals("Half board 1-3 pax", s.extraName("Half board 1-3 pax (number of guests to update manually)"))
        assertEquals("1 set of towels per person", s.extraName("1 set of towels per person - to be updated manually"))
        assertEquals("Boat4You Carefree surcharge", s.extraName("Navigare Carefree surcharge"))
        assertEquals("Boat4You Carefree surcharge for two/more weeks", s.extraName("Navigare Carefree surcharge for two/more weeks"))
        assertEquals("Skipper (Caribbean)", s.extraName("One man crew (Caribbean)"), "N1 still applies")
        assertEquals("Boat4You 4200 deposit", s.extraName("Moorings 4200 deposit"))
        assertEquals("Boat4You", s.extraName("NSS Charter (NSS)"))
        assertEquals("Fuel", s.extraName("Fuel (internal note: check with base)"))
        assertEquals("Fuel", s.extraName("Fuel (!!)"))
    }

    @Test
    fun `premises become neutral in names and in descriptions`() {
        val cases =
            listOf(
                "Mooring fees (first and last day at the Athenian's Pier)" to "Mooring fees (first and last day at the charter base pier)",
                "Payment processed through Hermes's base at Olympic Marina" to "Payment processed through the charter base at Olympic Marina",
                "First night at Ionian Catamarans' pontoon" to "First night at the charter base pontoon",
                "First & last night at the Dream Yacht Charter pontoon" to "First & last night at the charter base pontoon",
                "Transit log processed through one of Athenian's bases" to "Transit log processed through the charter base",
                "Check-in at More Yachting's Pier, in Nikiana" to "Check-in at the base pier in Nikiana",
                "Parking at The Moorings base" to "Parking at the charter base",
                "Athenian Yachts' Pier fee" to "The charter base pier fee",
                // the label form, without a preposition (review 14.10.2026)
                "Sunsail base fee" to "The charter base fee",
                "NSS office fee" to "The charter base office fee",
                "Transfer: Athens Airport - Athenian Yachts base Alimos (1-4 pax)" to "Transfer: Athens Airport - the charter base Alimos (1-4 pax)",
                "Navigare Yachting pontoon mooring" to "The charter base pontoon mooring",
                "Parking near NSS Charter office" to "Parking near the charter base office",
                "Opening hours of the NSS office: 8-20h" to "Opening hours of the charter base office: 8-20h",
                "SUNSAIL BASE fee" to "The charter base fee",
            )
        cases.forEach { (raw, expected) ->
            assertEquals(expected, s.extraName(raw), "name: $raw")
            assertEquals(expected, s.description(raw), "description: $raw")
        }
    }

    @Test
    fun `a confirm note keeps N3 although it ends in a premises noun`() {
        assertEquals("Transfer (to be confirmed by Boat4You)", s.extraName("Transfer (to be reconfirmed by NSS office)"))
        assertEquals("Transfer (to be confirmed by Boat4You)", s.description("Transfer (to be confirmed by NSS office)"))
    }

    @Test
    fun `partner names in capitals are replaced, ambiguous entries stay case-exact`() {
        assertEquals("Boat4You check-in pack", s.extraName("SUNSAIL check-in pack"))
        assertEquals("Boat4You check-in pack", s.description("SUNSAIL check-in pack"))
        assertEquals("Welcome drink by Boat4You", s.description("Welcome drink by NSS CHARTER"))
        assertEquals("Boat4You transfer", s.description("DREAM YACHT CHARTER transfer"))
        listOf("MOORINGS FLEET", "NAVIGARE A VELA").forEach { assertSame(it, s.description(it), it) }
    }

    @Test
    fun `Navigare opening a sentence before a lowercase word is the Italian verb, elsewhere it is the partner`() {
        listOf(
            "Navigare a vela in Sardegna è un sogno.",
            "Navigare tra le isole Eolie con questo catamarano.",
            "Catamarano spazioso. Navigare lungo la costa è facile.",
            "Il programma:\nNavigare verso Lipari.",
        ).forEach {
            assertSame(it, s.description(it), it)
            assertSame(it, s.extraName(it), it)
        }
        assertEquals("Boat4You Carefree surcharge", s.description("Navigare Carefree surcharge"))
        assertEquals("Prenota con Boat4You.", s.description("Prenota con Navigare."))
        assertEquals("Fuel by Boat4You", s.description("Fuel by Navigare"))
        assertEquals("Navigare a vela. Boat4You Carefree incluso.", s.description("Navigare a vela. Navigare Carefree incluso."))
        assertTrue(s.findings("Navigare a vela in Sardegna").none { it.rule == "N4" })
    }

    // ---- descriptions ------------------------------------------------------------------------------------------------

    @Test
    fun `disclaimer sentences naming a partner are dropped, customer terms are kept`() {
        val raw =
            "Athenian Yachts solely provides facilitation services to help the Charterer directly hire local freelance crew. " +
                "The crew allowance of up to EUR30/day/crew is paid on board. " +
                "Athenian Yachts shall not be liable for any act or omission of the crew."
        assertEquals("The crew allowance of up to EUR30/day/crew is paid on board.", s.description(raw))

        assertEquals(
            "Skipper hired locally.",
            s.description("Skipper hired locally. Hermes Yachting shall not be liable for the skipper."),
        )
        assertEquals("Fuel is paid at check-out.", s.description("Fuel is paid at check-out.\nThe Company will not be held liable for lost items."))
        assertNull(s.description("Athenian Yachts solely provides facilitation services."), "nothing left -> null")

        // kept: a liability sentence without a partner, deposits, check-in obligations
        val kept =
            listOf(
                "The charterer pays the deposit of 2,000 € at the base.",
                "Check-in obligations of the charterer: arrive by 17:00 and present the crew list.",
                "The skipper will not be liable for personal belongings.",
            )
        kept.forEach { assertSame(it, s.description(it), it) }
        assertEquals("Boat4You collects the deposit of 2,000 € at the base.", s.description("The Company collects the deposit of 2,000 € at the base."))
    }

    @Test
    fun `contacts - e-mail, URLs and prefixed phone numbers with at least 8 digits`() {
        assertEquals("Order the provisioning by e-mail.", s.description("Order the provisioning by e-mail info@athenian.gr."))
        assertEquals("Menu on", s.description("Menu on https://www.example-charter.com/menu"))
        assertEquals("Menu on", s.description("Menu on www.example-charter.com"))
        assertEquals("Book with the base", s.description("Book with the base athenian.gr"))
        assertEquals("Book the transfer:", s.description("Book the transfer: Tel. +30 210 123 4567"))
        assertEquals("Book the transfer", s.description("Book the transfer +385 21 123 456"))
        assertEquals("Call the base", s.description("Call the base mob: 0915551234"))
        assertEquals("Write to", s.description("Write to nss.charter.it"))
        assertEquals("Write to", s.description("Write to NSS.GR"))
        assertEquals("Book the transfer", s.description("Book the transfer +30-210-1234567"))
    }

    @Test
    fun `a missing space after a full stop is not a domain`() {
        listOf(
            "Spacious cockpit.It has a large saloon",
            "Fully equipped with a watermaker.Es gibt viel Platz.",
            "Fully equipped galley.Me and my crew",
            "Approx.Info at reception",
            "Four cabins.De luxe interior",
            "cabins.De",
            "Large saloon.it has a TV",
        ).forEach {
            assertSame(it, s.description(it), it)
            assertTrue(s.findings(it).none { f -> f.rule == "CONTACT" }, it)
        }
    }

    @Test
    fun `price lists are not phone numbers`() {
        listOf(
            "Extra bed +50/60/70/80 per week",
            "Price list: +10 20 30 40 50 EUR",
            "Price list: +100 200 300 400 €",
            "Supplement +10 20 30 40 50 per day",
            "Fees 2024: +10/+20/+30/+40",
        ).forEach {
            assertSame(it, s.description(it), it)
            assertSame(it, s.extraName(it), it)
        }
    }

    @Test
    fun `numbers that are not phone numbers stay`() {
        listOf(
            "+ deposit 600€",
            "1-8 pax",
            "Transfer APT Split 1-8 pax OW",
            "Valid 2019-2023",
            "Check-in 14:00",
            "VHF 16",
            "2 x 12V",
            "+30 € per day",
            "Tel. 2",
            "Call 2 days before arrival",
        ).forEach {
            assertSame(it, s.description(it), it)
            assertSame(it, s.extraName(it), it)
        }
    }

    @Test
    fun `ordinary words that look like a partner stay byte-identical`() {
        listOf(
            "BVI Cruising Tax",
            "Base expenses - marina fees",
            "Skipper 25/26- from 13m & Lagoon 42 (food extra)",
            "Gennaker for SO 410, Oceanis 46.1",
            "Gas (Sun Odyssey 349)",
            "Sail away on your dream yacht",
            "mooring fees",
            "Mooring fees",
            "Ocean Skills",
            "Athena Blu",
            "Welcome Pack with traditional Greek products",
            "Hermes is a Bavaria 46 Cruiser",
            "Athenian Riviera transfer",
            "reliable outboard",
            "Final cleaning Hermes",
        ).forEach {
                assertSame(it, s.description(it), it)
                assertSame(it, s.extraName(it), it)
            }
    }

    @Test
    fun `html is stripped, single line breaks kept`() {
        assertEquals("Fuel\nWater", s.description("<p>Fuel</p><p>Water</p>"))
        assertEquals("Line one\nLine two", s.description("Line one<br/>Line two"))
    }

    @Test
    fun `block-level html becomes line breaks and basic entities are decoded`() {
        assertEquals(
            "Air condition\nGenerator\nWatermaker",
            s.description("<ul><li>Air condition</li><li>Generator</li><li>Watermaker</li></ul>"),
        )
        assertEquals("Equipment:\nGenerator\nWatermaker", s.description("Equipment:<ul>\n  <li>Generator</li>\n  <li>Watermaker</li>\n</ul>"))
        assertEquals("Boat4You fleet\nSecond line", s.description("<strong>Sunsail</strong> fleet<div>Second line</div>"))
        assertEquals("Title\nText", s.description("<h3>Title</h3><p>Text</p>"))
        assertEquals("Fuel 50 l", s.description("<table><tr><td>Fuel</td><td>50 l</td></tr></table>"))
        assertEquals("Built in 2019 & refitted", s.description("Built in 2019 &amp; refitted"))
        assertEquals("\"Blue\" class, skipper's cabin, length < 12 m, > 4 berths", s.description("&quot;Blue&quot; class, skipper&#39;s cabin, length &lt; 12 m, &gt; 4 berths"))
        val escaped = "&amp;lt;b&amp;gt; stays text"
        assertSame(escaped, s.description(escaped), "an escaped entity is never decoded twice")
        assertEquals("&lt;b> stays text", s.description("&lt;b&gt; stays text"), "&lt; before a letter never becomes a tag")
    }

    @Test
    fun `null, blank and the CUSTOM guard`() {
        assertNull(s.extraName(null))
        assertEquals("", s.extraName(""))
        assertNull(s.description(null))
        assertNull(s.description("   "))
        assertEquals("NSS", s.extraName("NSS", partnerCopy = false))
        assertEquals("Boat4You", s.extraName("NSS", partnerCopy = true))
        assertEquals("by NSS", s.description("by NSS", partnerCopy = false))
        assertTrue(s.isPartnerCopy(EntryType.EXTERNAL))
        assertFalse(s.isPartnerCopy(EntryType.CUSTOM))
        assertFalse(s.isPartnerCopy(null))
    }

    @Test
    fun `idempotent on the whole fixture corpus`() {
        CORPUS.forEach { raw ->
            val name = s.extraName(raw)
            assertEquals(name, s.extraName(name), "extraName twice: $raw")
            val description = s.description(raw)
            assertEquals(description, s.description(description), "description twice: $raw")
        }
    }

    @Test
    fun `findings name the rule and the token`() {
        val f = s.findings("Early Check-in (to be reconfirmed by NSS office) at Athenian's Pier")
        assertTrue(f.any { it.rule == "N3" })
        assertTrue(f.any { it.rule == "N4" && it.token == "NSS" })
        assertTrue(f.any { it.rule == "P1" })
        assertTrue(s.findings("BVI Cruising Tax").isEmpty())
    }

    @Test
    fun `an agency name the list does not carry is reported, a listed one is not`() {
        assertEquals("Sea Breeze Yachting", s.unlistedAgencyName("Sea Breeze Yachting provides the skipper", "Sea Breeze Yachting d.o.o."))
        assertNull(s.unlistedAgencyName("Boat4You provides the skipper", "Kavas Yachting"))
        assertNull(s.unlistedAgencyName("anything", "Abc"), "too short to judge")
        assertEquals(
            "Boat4You",
            s.syncDescription(
                "NSS",
                hr.workspace.boat4you.domains.catalouge.jpa.Agency().apply {
                    id = 1
                    name = "NSS Charter"
                },
            ),
        )
    }

    companion object {
        val CORPUS =
            listOf(
                "Navbow Seascooter (+ deposit 600€ - to be confirmed by NSS)",
                "Early Check-in (14:00pm - to be reconfirmed by NSS office)",
                "Half board 1-3 pax (number of guests to update manually)",
                "1 set of towels per person - to be updated manually",
                "Navigare Carefree surcharge",
                "Mooring fees (first and last day at the Athenian's Pier)",
                "processed through Hermes's base at Olympic Marina",
                "one night at Ionian Catamarans' pontoon",
                "First & last night at the Dream Yacht Charter pontoon",
                "processed through one of Athenian's bases",
                "More Yachting's Pier, in Nikiana",
                "Athenian Yachts solely provides facilitation services. Athenian Yachts shall not be liable.",
                "The charterer pays the deposit of 2,000 € at the base",
                "BVI Cruising Tax",
                "Moorings 4200/3/3 Club (The Moorings)",
                "Sunsail 424 - Sunsail / Sunsail",
                "Tel. +30 210 123 4567, info@nss.example",
                "<p>Fuel&nbsp;policy</p>  full   to full",
                "The Company provides fuel. The Company will not be held liable.",
                "Sunsail base fee",
                "Transfer: Athens Airport - Athenian Yachts base Alimos (1-4 pax)",
                "Opening hours of the NSS office: 8-20h",
                "Navigare a vela in Sardegna. Navigare Carefree incluso.",
                "SUNSAIL check-in pack",
                "<ul><li>Air condition</li><li>Generator</li></ul>",
                "Built in 2019 &amp; refitted, &amp;lt;b&amp;gt;",
                "Spacious cockpit.It has a large saloon",
                "Extra bed +50/60/70/80 per week",
            )
    }
}
