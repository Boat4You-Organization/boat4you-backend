package hr.workspace.boat4you.domains.branding

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.openapitools.model.LanguageEnum
import org.springframework.mock.web.MockHttpServletRequest
import java.net.URI

class BrandResolverSitesTest {
    private val registry = BrandRegistry()
    private val resolver = BrandResolver(registry)

    private fun req(vararg h: Pair<String, String>) = MockHttpServletRequest().apply { h.forEach { (k, v) -> addHeader(k, v) } }

    @Test
    fun `all 7 sites resolve from Origin and Referer, www and apex`() {
        SiteIds.HOSTS.forEach { (host, id) ->
            listOf(host, "www.$host").forEach { h ->
                resolver.resolve(req("Origin" to "https://$h")).id shouldBe id
                resolver.resolve(req("Referer" to "https://$h/en/yachts/lagoon-42?x=1")).id shouldBe id
            }
        }
    }

    @Test
    fun `header first, then origin, else boat4you - never throws (BACKEND-31)`() {
        resolver.resolve(req("X-Boat4You-Brand" to "croatia-yachting", "Origin" to "https://www.boat4you.com")).id shouldBe "croatia-yachting"
        resolver.resolve(req("X-Boat4You-Brand" to "nope")).id shouldBe "boat4you"
        resolver.resolve(req()).id shouldBe "boat4you" // boat4you-web server action: no Origin, no header
        listOf("null", "::not a uri", "https://catamaran-charter-italy.com").forEach { resolver.resolve(req("Origin" to it)).id shouldBe "boat4you" }
    }

    @Test
    fun `registry and SiteIds agree`() {
        registry.byId.keys shouldBe SiteIds.ALL.toSet()
        registry.byId.values.forEach { SiteIds.fromHost(URI(it.websiteUrl).host) shouldBe it.id }
    }

    @Test
    fun `inquiry brands stay as they are, croatia-yachting carries its real data`() {
        registry.get("boat4you") shouldBe
            Brand(
                "boat4you",
                "Boat4You | Endless blue. Boats 4 you",
                "Endless blue. Boats 4 you",
                "info@boat4you.com",
                "info@boat4you.com",
                "https://www.boat4you.com",
                "info@boat4you.com",
                "+385 91 3000 009",
                "data/images/boat4you-logo-mark.png",
                "#ffd24a",
                LanguageEnum.EN,
            )
        registry.get(null).id shouldBe "boat4you"
        registry.get("catamaran-italy").websiteUrl shouldBe "https://www.catamarancharteritaly.com"
        registry.get("croatia-yachting") shouldBe
            Brand(
                "croatia-yachting",
                "Yacht Charter Croatia",
                "Yacht charter in Croatia",
                "info@boat4you.com",
                "info@boat4you.com",
                "https://www.croatia-yachting.com",
                "info@croatia-yachting.com",
                "+385 91 3000 009",
                "data/images/boat4you-logo-mark.png",
                "#0e63a8",
                LanguageEnum.EN,
            )
        registry.byId.values.map { it.recipientAddress }.distinct() shouldBe listOf("info@boat4you.com") // leads keep landing in info@
    }
}
