package hr.workspace.boat4you.domains.branding

import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class BrandAiProfileTest {
    private val r = BrandRegistry()
    private val cats = listOf(VesselType.CATAMARAN, VesselType.POWER_CATAMARAN)
    private val sister = setOf("de", "fr", "pt", "it", "es")

    @Test
    fun `fleets mirror each sister repo (markets config ALLOWED_COUNTRY_CODES, yacht actions ALLOWED_VESSEL_TYPES)`() {
        r.get("boat4you").ai shouldBe AiSiteProfile.BOAT4YOU
        AiSiteProfile.BOAT4YOU shouldBe
            AiSiteProfile(
                "boat4you",
                "Boat4You",
                "https://www.boat4you.com",
                "/boat/",
                null,
                emptyList(),
                emptyList(),
                "sailing yachts, catamarans, motor yachts and gulets",
                true,
                setOf("de", "fr", "it", "es", "pt", "nl", "pl", "hr"),
            )
        r.get("catamaran-croatia").ai shouldBe
            AiSiteProfile(
                "catamaran-croatia",
                "Catamaran Charter Croatia",
                "https://www.catamaran-croatia-charter.com",
                "/catamarans/",
                "Croatia",
                listOf("HR"),
                cats,
                "catamarans and power catamarans",
                false,
                sister,
            )
        r.get("catamaran-greece").ai shouldBe
            AiSiteProfile(
                "catamaran-greece",
                "Catamaran Charter Greece",
                "https://www.catamaran-charter-greece.com",
                "/catamarans/",
                "Greece",
                listOf("GR"),
                cats,
                "catamarans and power catamarans",
                false,
                sister,
            )
        r.get("catamaran-italy").ai shouldBe
            AiSiteProfile(
                "catamaran-italy",
                "Catamaran Charter Italy",
                "https://www.catamarancharteritaly.com",
                "/catamarans/",
                "Italy",
                listOf("IT"),
                cats,
                "catamarans and power catamarans",
                false,
                sister,
            )
        r.get("catamaran-caribbean").ai shouldBe
            AiSiteProfile(
                "catamaran-caribbean",
                "Catamaran Charter Caribbean",
                "https://www.catamaran-charter-caribbean.com",
                "/catamarans/",
                "the Caribbean",
                listOf("AG", "BS", "BZ", "BQ", "CU", "GD", "GP", "MQ", "LC", "MF", "VC", "SX", "VG", "VI"),
                cats,
                "catamarans and power catamarans",
                false,
                sister,
            )
        r.get("europe-yachts").ai shouldBe
            AiSiteProfile(
                "europe-yachts",
                "Europe Yachts Charter",
                "https://www.europe-yachts.com",
                "/yachts/",
                "Croatia, Greece, Italy, Spain and Türkiye",
                listOf("HR", "GR", "IT", "ES", "TR"),
                emptyList(),
                "sailing yachts, catamarans, motor yachts and gulets",
                false,
                sister,
            )
        r.get("croatia-yachting").ai shouldBe
            AiSiteProfile(
                "croatia-yachting",
                "Yacht Charter Croatia",
                "https://www.croatia-yachting.com",
                "/yachts/",
                "Croatia",
                listOf("HR"),
                emptyList(),
                "sailing yachts, catamarans, motor yachts and gulets",
                false,
                sister,
            )
    }

    @Test
    fun `the AI links, the inquiry e-mail links and the tracker use one origin per site, byte for byte (SISTER-1)`() {
        r.byId.values.forEach { b ->
            b.ai.baseUrl shouldBe b.websiteUrl
            SiteIds.fromHost(URI(b.ai.baseUrl).host) shouldBe b.id
            b.ai.siteId shouldBe b.id
        }
    }

    @Test
    fun `locale prefix - English has none, unknown has none`() {
        r.get("catamaran-greece").ai.localePrefix("de-AT") shouldBe "/de"
        r.get("catamaran-greece").ai.localePrefix("en") shouldBe ""
        r.get("catamaran-greece").ai.localePrefix("hr") shouldBe ""
        AiSiteProfile.BOAT4YOU.localePrefix("hr") shouldBe "/hr"
    }
}
