package hr.workspace.boat4you.domains.review

import hr.workspace.boat4you.domains.review.service.ReviewLabels
import hr.workspace.boat4you.domains.review.service.ReviewLinks
import hr.workspace.boat4you.domains.review.service.ReviewTokens
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

class ReviewTokensTests {
    @Test
    fun `token - 32 random bytes as 43 url-safe characters, never repeated`() {
        val tokens = (1..500).map { ReviewTokens.generate() }
        tokens.forEach { token ->
            token.length shouldBe 43
            ReviewTokens.isWellFormed(token) shouldBe true
            Base64.getUrlDecoder().decode(token).size shouldBe 32
        }
        tokens.toSet().size shouldBe tokens.size
    }

    @Test
    fun `hash - hex SHA-256, deterministic, never the token itself`() {
        // FIPS 180-2 test vector.
        ReviewTokens.hash("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val token = ReviewTokens.generate()
        ReviewTokens.hash(token) shouldBe ReviewTokens.hash(token)
        ReviewTokens.hash(token).length shouldBe 64
        ReviewTokens.hash(token) shouldNotBe token
        ReviewTokens.hash(token) shouldNotBe ReviewTokens.hash(ReviewTokens.generate())
    }

    @Test
    fun `well-formed check refuses anything that cannot be one of our tokens`() {
        listOf("", "abc", "a".repeat(42), "a".repeat(44), "a".repeat(42) + "=", "a".repeat(42) + "/", "a".repeat(42) + "+")
            .forEach { ReviewTokens.isWellFormed(it) shouldBe false }
        ReviewTokens.isWellFormed("A-_".repeat(14) + "z") shouldBe true
    }

    @Test
    fun `link expires exactly at expiresAt, 60 days after sending`() {
        ReviewTokens.VALIDITY shouldBe Duration.ofDays(60)
        val expiresAt = Instant.parse("2026-11-24T09:10:00Z")
        ReviewTokens.isExpired(expiresAt, expiresAt.minusSeconds(1)) shouldBe false
        ReviewTokens.isExpired(expiresAt, expiresAt) shouldBe true
        ReviewTokens.isExpired(expiresAt, expiresAt.plusSeconds(1)) shouldBe true
    }

    @Test
    fun `review stays editable for 24 hours after the first submit`() {
        val created = Instant.parse("2026-09-25T10:00:00Z")
        ReviewTokens.editableUntil(created) shouldBe Instant.parse("2026-09-26T10:00:00Z")
        ReviewTokens.isEditable(created, created.plus(Duration.ofHours(23))) shouldBe true
        ReviewTokens.isEditable(created, created.plus(Duration.ofHours(24))) shouldBe false
    }

    @Test
    fun `review link - English without prefix, other languages with their next-intl prefix`() {
        ReviewLinks.reviewUrl("https://www.boat4you.com", "en", "TOKEN") shouldBe "https://www.boat4you.com/review/TOKEN"
        ReviewLinks.reviewUrl("https://www.boat4you.com/", "de", "TOKEN") shouldBe "https://www.boat4you.com/de/review/TOKEN"
        ReviewLinks.reviewUrl("https://www.boat4you.com", "xx", "TOKEN") shouldBe "https://www.boat4you.com/review/TOKEN"
        ReviewLinks.normalizeLocale("HR") shouldBe "hr"
        ReviewLinks.normalizeLocale("pt-BR") shouldBe "pt"
        ReviewLinks.normalizeLocale("ja") shouldBe null
        ReviewLinks.normalizeLocale(null) shouldBe null
    }

    @Test
    fun `yacht label - manufacturer, model and name without repeating the manufacturer`() {
        ReviewLabels.yachtFullLabel("Beneteau", "Oceanis 46.1", "Tria") shouldBe "Beneteau Oceanis 46.1 Tria"
        ReviewLabels.yachtFullLabel("Bavaria", "Bavaria Cruiser 46", "Tria") shouldBe "Bavaria Cruiser 46 Tria"
        ReviewLabels.yachtFullLabel("Lagoon", "lagoon 42", " ") shouldBe "lagoon 42"
        ReviewLabels.yachtFullLabel(null, null, "Fortuna") shouldBe "Fortuna"
        ReviewLabels.yachtFullLabel(null, null, null) shouldBe "—"
    }
}
