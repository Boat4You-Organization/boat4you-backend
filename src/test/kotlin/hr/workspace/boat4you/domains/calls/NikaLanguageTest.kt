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
    fun `a call starts in the language of the number's country`() {
        NikaLanguage.forCall("+33612345678", "en") shouldBe "fr"
        NikaLanguage.forCall("+385981234567", "en") shouldBe "hr"
        NikaLanguage.forCall("+4915112345678", null) shouldBe "de"
        NikaLanguage.forCall("+5511912345678", "es") shouldBe "pt"
        NikaLanguage.forCall("+50499123456", "en") shouldBe "es" // Honduras
        NikaLanguage.forCall("+17875550100", "en") shouldBe "es" // Puerto Rico
    }

    @Test
    fun `a number from a country whose language Nika does not speak is greeted in English, whatever the form`() {
        NikaLanguage.forCall("+96550012345", "de") shouldBe "en" // Kuwait
        NikaLanguage.forCall("+966501234567", "fr") shouldBe "en" // Saudi Arabia
        NikaLanguage.forCall("+447911123456", "hr") shouldBe "en"
        NikaLanguage.forCall("+12125550100", "it") shouldBe "en"
    }

    @Test
    fun `in a multilingual country the form picks among its languages, else the most spoken one`() {
        NikaLanguage.forCall("+41791234567", "fr-CH") shouldBe "fr"
        NikaLanguage.forCall("+41791234567", "hr") shouldBe "de"
        NikaLanguage.forCall("+32470123456", null) shouldBe "nl"
    }

    @Test
    fun `a missing or unreadable number leaves the form language`() {
        NikaLanguage.forCall(null, "pl") shouldBe "pl"
        NikaLanguage.forCall("not a number", "it") shouldBe "it"
        NikaLanguage.forCall("", "cs") shouldBe "en"
        NikaLanguage.forCall("385981234567", "pl") shouldBe "pl" // no plus sign
        NikaLanguage.forCall("+80012345678", "de") shouldBe "de" // international freephone: no country
        NikaLanguage.forCall("+870773111632", "fr") shouldBe "fr" // satellite
    }

    @Test
    fun `the nine languages are the ones the forms are written in`() {
        NikaLanguage.SPOKEN shouldBe setOf("en", "hr", "de", "es", "fr", "it", "nl", "pl", "pt")
    }
}
