package hr.workspace.boat4you.domains.catalouge.utils

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * 24.9.2026: NSS Charter and Marina Yacht Charter sell the skipper as "One man crew". Stored names read "Skipper"
 * (customer extras tab, e-mails, admin offer builder keyword match); nothing else may change. The expectations
 * mirror what V9_60 did to the existing rows (run against PostgreSQL 17 with the same strings).
 */
class ExtraNameNormalizerTests {
    @Test
    fun `the three real partner names become Skipper and keep their suffix`() {
        ExtraNameNormalizer.normalize("One man crew (Caribbean)") shouldBe "Skipper (Caribbean)"
        ExtraNameNormalizer.normalize("One man crew (+ boarding)") shouldBe "Skipper (+ boarding)"
        ExtraNameNormalizer.normalize("One man crew (+ boarding) : Compensation due directly to the Skipper, to be paid on board") shouldBe
            "Skipper (+ boarding) : Compensation due directly to the Skipper, to be paid on board"
    }

    @Test
    fun `case, hyphen and spacing variants are matched`() {
        ExtraNameNormalizer.normalize("one-man crew") shouldBe "Skipper"
        ExtraNameNormalizer.normalize("Oneman crew") shouldBe "Skipper"
        ExtraNameNormalizer.normalize("One man crew") shouldBe "Skipper"
        ExtraNameNormalizer.normalize("One-Man Crew / day") shouldBe "Skipper / day"
    }

    @Test
    fun `a renamed name gets its double spaces collapsed and its ends trimmed`() {
        ExtraNameNormalizer.normalize("ONE  MAN  CREW  (x)") shouldBe "Skipper (x)"
        ExtraNameNormalizer.normalize("  One man crew   (Caribbean) ") shouldBe "Skipper (Caribbean)"
    }

    @Test
    fun `anything else is returned untouched`() {
        listOf(
            "Two man crew",
            "Crew change",
            "Crew list",
            "Skipper",
            "someone man crew",
            "One man crewed yacht",
            // No general whitespace cleanup: stored names are join keys (extrasKey(), NauSys service lookup).
            "Comfort Pack  (Final Cleaning) ",
        ).forEach { name -> ExtraNameNormalizer.normalize(name) shouldBeSameInstanceAs name }
    }

    // 21.10.2026 (EY SEO regression T12): the ALL-CAPS trailing "RESERVED IN ADVANCE" reads "(reserved in advance)".
    @Test
    fun `the EY name gets a lower-case reserved in advance parenthetical`() {
        ExtraNameNormalizer.normalize("Early check in (at 13:00) RESERVED IN ADVANCE") shouldBe "Early check in (at 13:00) (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in (at 13:00)  RESERVED  IN ADVANCE! ") shouldBe "Early check in (at 13:00) (reserved in advance)"
        ExtraNameNormalizer.normalize("RESERVED IN ADVANCE") shouldBe "(reserved in advance)"
    }

    @Test
    fun `a bracketed or dashed RESERVED IN ADVANCE loses its own brackets and dashes`() {
        ExtraNameNormalizer.normalize("Early check in (RESERVED IN ADVANCE)") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in (RESERVED IN ADVANCE!)") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in [RESERVED IN ADVANCE]") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in - RESERVED IN ADVANCE!") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Late check out \u2013 RESERVED IN ADVANCE \u2013") shouldBe "Late check out (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in: (RESERVED IN ADVANCE)") shouldBe "Early check in (reserved in advance)"
    }

    // A no-break space (a decoded &nbsp; in partner text) is whitespace for the rule, and it does not survive the rename.
    @Test
    fun `a no-break space around or inside RESERVED IN ADVANCE is matched and dropped`() {
        ExtraNameNormalizer.normalize("Early check in\u00A0RESERVED IN ADVANCE") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in \u00A0RESERVED IN ADVANCE") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in RESERVED\u00A0IN\u00A0ADVANCE\u00A0") shouldBe "Early check in (reserved in advance)"
        ExtraNameNormalizer.normalize("Early check in -\u00A0(\u00A0RESERVED IN ADVANCE\u00A0)") shouldBe "Early check in (reserved in advance)"
    }

    @Test
    fun `reserved in advance that is not ALL-CAPS or not trailing is left alone`() {
        listOf(
            "Early check in (at 13:00) (reserved in advance)",
            "Early check in - reserved in advance",
            "Early check in (Reserved in advance)",
            "RESERVED IN ADVANCE: early check in",
        ).forEach { name -> ExtraNameNormalizer.normalize(name) shouldBeSameInstanceAs name }
    }

    @Test
    fun `acronyms stay upper-case`() {
        listOf("APA", "APA (Advance Provisioning Allowance)", "VAT", "VHF radio", "BVI Cruising Tax", "GPS", "WC chemicals", "AC", "SUP", "TV", "USB")
            .forEach { name -> ExtraNameNormalizer.normalize(name) shouldBeSameInstanceAs name }
        ExtraNameNormalizer.normalize("APA - RESERVED IN ADVANCE") shouldBe "APA (reserved in advance)"
        ExtraNameNormalizer.normalize("SUP board (VAT incl.) RESERVED IN ADVANCE") shouldBe "SUP board (VAT incl.) (reserved in advance)"
    }

    @Test
    fun `a normalized name normalizes to itself`() {
        listOf("Early check in (at 13:00) RESERVED IN ADVANCE", "Early check in [RESERVED IN ADVANCE]", "One man crew RESERVED IN ADVANCE")
            .map { ExtraNameNormalizer.normalize(it)!! }
            .forEach { name -> ExtraNameNormalizer.normalize(name) shouldBeSameInstanceAs name }
        ExtraNameNormalizer.normalize("One man crew RESERVED IN ADVANCE") shouldBe "Skipper (reserved in advance)"
    }

    @Test
    fun `null and blank are returned as they are`() {
        ExtraNameNormalizer.normalize(null) shouldBe null
        ExtraNameNormalizer.normalize("") shouldBe ""
        ExtraNameNormalizer.normalize("   ") shouldBe "   "
    }
}
