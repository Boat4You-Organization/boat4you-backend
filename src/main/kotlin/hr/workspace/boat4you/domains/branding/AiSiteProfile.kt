package hr.workspace.boat4you.domains.branding

import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.util.Locale

/**
 * What the AI assistant (IRIS) may say and search for on ONE public site: which boats the site sells,
 * where, and how a boat page of that site is linked. Held on [Brand.ai] with no default, so a brand
 * without a fleet does not compile, and so there is one list per site that the prompt, the search
 * tools and the links all read (keep-list SISTER-1: no second list that can drift).
 *
 * The values mirror each sister repo at its `main` on 26.10.2026: Catamaran Croatia Charter 7f39854,
 * Catamaran Charter Greece 17b27735, Catamaran Charter Italy 41b78b0, Catamaran Charter Caribbean
 * 8571488 (`countries.config.tsx`, `CARIBBEAN_COUNTRY_CODES`), Europe Yachts 875d8356, Croatia
 * Yachting c93597cb: `ALLOWED_COUNTRY_CODES` in the markets config and `ALLOWED_VESSEL_TYPES` in
 * the yacht actions. `SITE_VESSEL_TYPES` / `SITE_COUNTRY_CODES` in `campaigns.config.ts` are promo
 * only and are NOT the fleet.
 *
 * @property siteId one of [hr.workspace.boat4you.domains.contactcenter.SiteIds.ALL], equal to [Brand.id].
 * @property baseUrl the site's canonical origin, no trailing slash: byte for byte [Brand.websiteUrl], so the
 *   AI links, the inquiry e-mail links and the tracker name a page on one host.
 * @property boatPath path in front of a boat slug, with both slashes (`/boat/`, `/catamarans/`, `/yachts/`).
 * @property region the place name the AI uses in prose (`Croatia`, `the Caribbean`), null on the whole-world site.
 * @property countryCodes ISO 3166-1 alpha-2 codes the site sells in; empty = every country.
 * @property vesselTypes catalogue vessel types the site sells; empty = every type.
 * @property fleetLabel what the site sells, as a phrase for the prompt.
 * @property blogGuides true when the site has a blog the AI may link guides from.
 * @property prefixedLocales two-letter locales that the site puts in front of its path (`/de/...`); the
 *   others, English included, are served without a prefix.
 */
data class AiSiteProfile(
    val siteId: String,
    val siteName: String,
    val baseUrl: String,
    val boatPath: String,
    val region: String?,
    val countryCodes: List<String>,
    val vesselTypes: List<VesselType>,
    val fleetLabel: String,
    val blogGuides: Boolean,
    val prefixedLocales: Set<String>,
) {
    /** True when the site sells in some countries only, so the AI has to keep its searches inside them. */
    val scoped: Boolean get() = countryCodes.isNotEmpty()

    /** `/de` for `de-AT`, empty for English, for a locale the site has no prefix for, and for null. */
    fun localePrefix(locale: String?): String =
        locale
            ?.take(2)
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in prefixedLocales }
            ?.let { "/$it" }
            ?: ""

    companion object {
        /** The master site: the whole fleet in every country, with the blog guides. */
        val BOAT4YOU =
            AiSiteProfile(
                siteId = "boat4you",
                siteName = "Boat4You",
                baseUrl = "https://www.boat4you.com",
                boatPath = "/boat/",
                region = null,
                countryCodes = emptyList(),
                vesselTypes = emptyList(),
                fleetLabel = "sailing yachts, catamarans, motor yachts and gulets",
                blogGuides = true,
                prefixedLocales = setOf("de", "fr", "it", "es", "pt", "nl", "pl", "hr"),
            )
    }
}
