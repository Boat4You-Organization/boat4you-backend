package hr.workspace.boat4you.domains.branding

import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import org.openapitools.model.LanguageEnum
import org.springframework.stereotype.Component

/**
 * Static registry of every front-end brand that talks to this backend.
 *
 * Each entry has a stable [Brand.id] used over the wire (the
 * `X-Boat4You-Brand` header). Default fallback is [DEFAULT_ID] — used
 * for traffic that doesn't declare a brand (legacy clients, curl tests,
 * scripts) so emails still go out with sane content rather than
 * crashing the request.
 *
 * Catamaran-* and Europe Yachts brands ship with **placeholder**
 * From / recipient / logo entries that point back at the Boat4You
 * mailbox + Boat4You logo. They MUST be filled with the real per-brand
 * mailbox + logo PNG before the front-ends go live; until then any
 * inquiry from those sites still lands in info@boat4you.com so leads
 * aren't lost. Marker comments below flag every TODO field.
 */
@Component
class BrandRegistry {
    val byId: Map<String, Brand> =
        listOf(
            // ───────── Boat4You — fully configured (master broker brand) ─────────
            Brand(
                id = "boat4you",
                displayName = "Boat4You | Endless blue. Boats 4 you",
                tagline = "Endless blue. Boats 4 you",
                fromAddress = "info@boat4you.com",
                recipientAddress = "info@boat4you.com",
                websiteUrl = "https://www.boat4you.com",
                supportEmail = "info@boat4you.com",
                supportPhone = "+385 91 3000 009",
                logoMarkClasspath = "data/images/boat4you-logo-mark.png",
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN,
                ai = AiSiteProfile.BOAT4YOU,
            ),
            // ───────── Catamaran Croatia Charter — TODO: real assets ─────────
            // Front-end: catamaran-croatia-charter.com (front-end repo:
            // /catamaran-croatia-charter — still zipped). Mario to provide:
            // logo PNG (square mark), confirmed From/recipient mailbox,
            // tagline, support phone. Until then placeholders route through
            // boat4you so leads don't drop.
            Brand(
                id = "catamaran-croatia",
                displayName = "Catamaran Croatia Charter",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO replace once mailbox provisioned
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://www.catamaran-croatia-charter.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO replace with brand logo
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.HR, // Croatian-managed brand → admins read inquiry email in Croatian
                ai =
                    AiSiteProfile(
                        siteId = "catamaran-croatia",
                        siteName = "Catamaran Charter Croatia",
                        baseUrl = "https://www.catamaran-croatia-charter.com",
                        boatPath = "/catamarans/",
                        region = "Croatia",
                        countryCodes = listOf("HR"),
                        vesselTypes = CATAMARAN_TYPES,
                        fleetLabel = "catamarans and power catamarans",
                        blogGuides = false,
                        prefixedLocales = SISTER_LOCALES,
                    ),
            ),
            // ───────── Catamaran Charter Greece — TODO ─────────
            Brand(
                id = "catamaran-greece",
                displayName = "Catamaran Charter Greece",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://www.catamaran-charter-greece.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN, // Greek not in our locale set → admins read in EN until staff confirms preference
                ai =
                    AiSiteProfile(
                        siteId = "catamaran-greece",
                        siteName = "Catamaran Charter Greece",
                        baseUrl = "https://www.catamaran-charter-greece.com",
                        boatPath = "/catamarans/",
                        region = "Greece",
                        countryCodes = listOf("GR"),
                        vesselTypes = CATAMARAN_TYPES,
                        fleetLabel = "catamarans and power catamarans",
                        blogGuides = false,
                        prefixedLocales = SISTER_LOCALES,
                    ),
            ),
            // ───────── Catamaran Charter Italy — TODO ─────────
            Brand(
                id = "catamaran-italy",
                displayName = "Catamaran Charter Italy",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://www.catamarancharteritaly.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.IT,
                ai =
                    AiSiteProfile(
                        siteId = "catamaran-italy",
                        siteName = "Catamaran Charter Italy",
                        baseUrl = "https://www.catamarancharteritaly.com",
                        boatPath = "/catamarans/",
                        region = "Italy",
                        countryCodes = listOf("IT"),
                        vesselTypes = CATAMARAN_TYPES,
                        fleetLabel = "catamarans and power catamarans",
                        blogGuides = false,
                        prefixedLocales = SISTER_LOCALES,
                    ),
            ),
            // ───────── Catamaran Charter Caribbean — TODO ─────────
            Brand(
                id = "catamaran-caribbean",
                displayName = "Catamaran Charter Caribbean",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://www.catamaran-charter-caribbean.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN,
                ai =
                    AiSiteProfile(
                        siteId = "catamaran-caribbean",
                        siteName = "Catamaran Charter Caribbean",
                        baseUrl = "https://www.catamaran-charter-caribbean.com",
                        boatPath = "/catamarans/",
                        region = "the Caribbean",
                        countryCodes = listOf("AG", "BS", "BZ", "BQ", "CU", "GD", "GP", "MQ", "LC", "MF", "VC", "SX", "VG", "VI"),
                        vesselTypes = CATAMARAN_TYPES,
                        fleetLabel = "catamarans and power catamarans",
                        blogGuides = false,
                        prefixedLocales = SISTER_LOCALES,
                    ),
            ),
            // ───────── Europe Yachts — TODO ─────────
            // Already unpacked at /europe-yachts/europe-yachts-web-main and
            // points NEXT_PUBLIC_BOAT_WS_API_URL at api.boat4you.com. Add
            // X-Boat4You-Brand header in their inquiry POST.
            Brand(
                id = "europe-yachts",
                displayName = "Europe Yachts",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO replace once mailbox confirmed
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://www.europe-yachts.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN,
                ai =
                    AiSiteProfile(
                        siteId = "europe-yachts",
                        siteName = "Europe Yachts Charter",
                        baseUrl = "https://www.europe-yachts.com",
                        boatPath = "/yachts/",
                        region = "Croatia, Greece, Italy, Spain and Türkiye",
                        countryCodes = listOf("HR", "GR", "IT", "ES", "TR"),
                        vesselTypes = emptyList(),
                        fleetLabel = "sailing yachts, catamarans, motor yachts and gulets",
                        blogGuides = false,
                        prefixedLocales = SISTER_LOCALES,
                    ),
            ),
            // ───────── Croatia Yachting — real site data, shared boat4you mailbox ─────────
            // Name, tagline, site URL and accent colour: croatia-yachting-web-main
            // src/config/pdf-brand.config.ts; contact email + phone: contactInfo.config.ts.
            // From / recipient stay on the boat4you mailbox (SMTP logs in as it, and leads
            // must keep landing in info@boat4you.com), like the other sister brands.
            Brand(
                id = "croatia-yachting",
                displayName = "Yacht Charter Croatia",
                tagline = "Yacht charter in Croatia",
                fromAddress = "info@boat4you.com",
                recipientAddress = "info@boat4you.com",
                websiteUrl = "https://www.croatia-yachting.com",
                supportEmail = "info@croatia-yachting.com",
                supportPhone = "+385 91 3000 009",
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO replace with brand logo
                accentColor = "#0e63a8",
                defaultLanguage = LanguageEnum.EN,
                ai =
                    AiSiteProfile(
                        siteId = "croatia-yachting",
                        siteName = "Yacht Charter Croatia",
                        baseUrl = "https://www.croatia-yachting.com",
                        boatPath = "/yachts/",
                        region = "Croatia",
                        countryCodes = listOf("HR"),
                        vesselTypes = emptyList(),
                        fleetLabel = "sailing yachts, catamarans, motor yachts and gulets",
                        blogGuides = false,
                        prefixedLocales = SISTER_LOCALES,
                    ),
            ),
        ).associateBy { it.id }

    fun get(id: String?): Brand =
        byId[id?.lowercase()?.trim()] ?: byId[DEFAULT_ID]
            ?: error("Default brand '$DEFAULT_ID' missing from registry — fix BrandRegistry.")

    val default: Brand get() = byId[DEFAULT_ID]!!

    companion object {
        const val DEFAULT_ID = "boat4you"

        /** The two catalogue types the catamaran sites sell (`ALLOWED_VESSEL_TYPES` in their yacht actions). */
        private val CATAMARAN_TYPES = listOf(VesselType.CATAMARAN, VesselType.POWER_CATAMARAN)

        /** Locales the six sister sites put in front of their paths; English is served without a prefix. */
        private val SISTER_LOCALES = setOf("de", "fr", "pt", "it", "es")
    }
}
