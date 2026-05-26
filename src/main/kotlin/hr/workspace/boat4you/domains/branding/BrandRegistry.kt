package hr.workspace.boat4you.domains.branding

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
                websiteUrl = "https://catamaran-croatia-charter.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO replace with brand logo
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.HR, // Croatian-managed brand → admins read inquiry email in Croatian
            ),

            // ───────── Catamaran Charter Greece — TODO ─────────
            Brand(
                id = "catamaran-greece",
                displayName = "Catamaran Charter Greece",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://catamaran-charter-greece.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN, // Greek not in our locale set → admins read in EN until staff confirms preference
            ),

            // ───────── Catamaran Charter Italy — TODO ─────────
            Brand(
                id = "catamaran-italy",
                displayName = "Catamaran Charter Italy",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://catamaran-charter-italy.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.IT,
            ),

            // ───────── Catamaran Charter Caribbean — TODO ─────────
            Brand(
                id = "catamaran-caribbean",
                displayName = "Catamaran Charter Caribbean",
                tagline = null,
                fromAddress = "info@boat4you.com", // TODO
                recipientAddress = "info@boat4you.com", // TODO
                websiteUrl = "https://catamaran-charter-caribbean.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN,
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
                websiteUrl = "https://europe-yachts.com",
                supportEmail = "info@boat4you.com", // TODO
                supportPhone = null,
                logoMarkClasspath = "data/images/boat4you-logo-mark.png", // TODO
                accentColor = "#ffd24a",
                defaultLanguage = LanguageEnum.EN,
            ),
        ).associateBy { it.id }

    fun get(id: String?): Brand =
        byId[id?.lowercase()?.trim()] ?: byId[DEFAULT_ID]
            ?: error("Default brand '$DEFAULT_ID' missing from registry — fix BrandRegistry.")

    val default: Brand get() = byId[DEFAULT_ID]!!

    companion object {
        const val DEFAULT_ID = "boat4you"
    }
}
