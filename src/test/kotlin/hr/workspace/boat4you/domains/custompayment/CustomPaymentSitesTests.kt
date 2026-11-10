package hr.workspace.boat4you.domains.custompayment

import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentLinkKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CustomPaymentSitesTests {
    private val keyA = "a".repeat(32)
    private val keyB = "B-_9".repeat(10)

    @Test
    fun `the 7 sites with www origins, their locales and brand names`() {
        CustomPaymentSites.KEYS shouldContainExactly
            setOf(
                "boat4you",
                "catamaran-charter-greece",
                "catamaran-croatia-charter",
                "catamarancharteritaly",
                "catamaran-charter-caribbean",
                "europe-yachts",
                "croatia-yachting",
            )
        CustomPaymentSites.ALL.forEach { site ->
            site.baseUrl.startsWith("https://www.") shouldBe true
            site.baseUrl.endsWith("/") shouldBe false
            site.locales.first() shouldBe "en"
        }
        CustomPaymentSites.byKey("croatia-yachting")!!.brandName shouldBe "Yacht Charter Croatia"
        CustomPaymentSites.byKey("boat4you")!!.locales.size shouldBe 9
        CustomPaymentSites.byKey("europe-yachts")!!.locales.size shouldBe 6
        CustomPaymentSites.byKey("evil.example") shouldBe null
    }

    @Test
    fun `locale prefix - none for en or a locale the site does not have`() {
        val boat4you = CustomPaymentSites.byKey("boat4you")!!
        val greece = CustomPaymentSites.byKey("catamaran-charter-greece")!!
        boat4you.localePrefix("en") shouldBe ""
        boat4you.localePrefix(null) shouldBe ""
        boat4you.localePrefix("de") shouldBe "/de"
        boat4you.localePrefix("hr") shouldBe "/hr"
        greece.localePrefix("hr") shouldBe ""
        greece.localePrefix("pt") shouldBe "/pt"
    }

    @Test
    fun `link keys - several keys per site, constant-time match, junk entries dropped`() {
        val keys = CustomPaymentLinkKeys(" boat4you = $keyA , $keyB ; europe-yachts=$keyB ; unknown-site=$keyA ; croatia-yachting=short ; nonsense ")
        keys.resolve("boat4you", keyA)!!.key shouldBe "boat4you"
        keys.resolve("boat4you", " $keyB ")!!.key shouldBe "boat4you"
        keys.resolve("europe-yachts", keyB)!!.key shouldBe "europe-yachts"
        keys.resolve("europe-yachts", keyA) shouldBe null
        keys.resolve("croatia-yachting", "short") shouldBe null
        keys.resolve("unknown-site", keyA) shouldBe null
        keys.resolve("boat4you", null) shouldBe null
        keys.resolve("boat4you", "") shouldBe null
        keys.resolve(null, keyA) shouldBe null
        keys.summary() shouldBe mapOf("boat4you" to 2, "europe-yachts" to 1)
        CustomPaymentLinkKeys("").summary() shouldBe emptyMap()
    }
}
