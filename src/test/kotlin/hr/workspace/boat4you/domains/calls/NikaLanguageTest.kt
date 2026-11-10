package hr.workspace.boat4you.domains.calls

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Nika speaks the nine languages of the inquiry forms; anything else is answered in English (ruling M16). */
class NikaLanguageTest {
    @Test
    fun `a language Nika speaks is kept`() {
        NikaLanguage.of("hr") shouldBe "hr"
        NikaLanguage.of("pt") shouldBe "pt"
    }

    @Test
    fun `a regional code is cut to its language, whatever the case or separator`() {
        NikaLanguage.of("de-AT") shouldBe "de"
        NikaLanguage.of("EN_gb") shouldBe "en"
        NikaLanguage.of(" It ") shouldBe "it"
    }

    @Test
    fun `a language Nika does not speak, an empty or a missing one is English`() {
        NikaLanguage.of("cs") shouldBe "en"
        NikaLanguage.of("sl") shouldBe "en"
        NikaLanguage.of("h") shouldBe "en"
        NikaLanguage.of("") shouldBe "en"
        NikaLanguage.of(null) shouldBe "en"
    }

    @Test
    fun `the nine languages are the ones the forms are written in`() {
        NikaLanguage.SPOKEN shouldBe setOf("en", "hr", "de", "es", "fr", "it", "nl", "pl", "pt")
    }
}
