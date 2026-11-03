package hr.workspace.boat4you.domains.chat.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChatTextsTest {
    private val texts =
        listOf<(String?) -> String>(
            ChatTexts::failureApology,
            { ChatTexts.handoffHolding(it, false) },
            { ChatTexts.handoffHolding(it, true) },
            ChatTexts::aiPaused,
            ChatTexts::itinerariesTitle,
        )

    @Test
    fun `nine languages, English fallback, distinct texts`() {
        ChatTexts.LOCALES shouldBe listOf("en", "hr", "fr", "de", "pt", "it", "es", "pl", "nl")
        texts.forEach { f ->
            ChatTexts.LOCALES.map(f).toSet().size shouldBe 9
            f("tr") shouldBe f("en")
            f("de-AT") shouldBe f("de")
            f(null) shouldBe f("en")
        }
        ChatTexts.failureApology("en") shouldBe
            "Sorry — I hit a technical hiccup just now. Please try again in a moment, or ask for our team and a person will reply here."
    }

    @Test
    fun `no text trips the output guard or names the AI tools`() {
        ChatTexts.LOCALES.flatMap { l -> texts.map { it(l) } }.forEach { ChatSecurityGuard.checkOutput(it, "0123456789abcdef").shouldBeNull() }
    }
}
