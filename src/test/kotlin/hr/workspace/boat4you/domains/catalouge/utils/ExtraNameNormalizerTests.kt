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

    @Test
    fun `null and blank are returned as they are`() {
        ExtraNameNormalizer.normalize(null) shouldBe null
        ExtraNameNormalizer.normalize("") shouldBe ""
        ExtraNameNormalizer.normalize("   ") shouldBe "   "
    }
}
