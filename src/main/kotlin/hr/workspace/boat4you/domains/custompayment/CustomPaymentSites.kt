package hr.workspace.boat4you.domains.custompayment

/**
 * One of the 7 sites that serve the secret `/pay/<linkKey>` page. [baseUrl] is the canonical origin (with `www`,
 * no trailing slash); [locales] are the site's next-intl locales; [brandName] is what the payer sees (Revolut order
 * description, e-mails) — the site's own brand, never another site's or a charter agency's.
 */
data class CustomPaymentSite(
    val key: String,
    val baseUrl: String,
    val locales: List<String>,
    val brandName: String,
) {
    fun supportsLocale(locale: String): Boolean = locale in locales

    /** `""` for the default locale `en` (next-intl `as-needed`), `/<locale>` for another locale of this site. */
    fun localePrefix(locale: String?): String = if (locale == null || locale == CustomPaymentSites.DEFAULT_LOCALE || !supportsLocale(locale)) "" else "/$locale"
}

/**
 * Explicit allowlist of the sites a custom payment may come from (also the `site` CHECK of V9_80). Deliberately NOT
 * `domains/branding/BrandRegistry`: that registry has no Yacht Charter Croatia entry and an outdated Italy domain.
 * Brand names = each site's `meta.name`, except Boat4You; croatia-yachting is "Yacht Charter Croatia" (never the old
 * brand).
 */
object CustomPaymentSites {
    const val DEFAULT_LOCALE = "en"

    private val BOAT4YOU_LOCALES = listOf("en", "de", "es", "fr", "hr", "it", "nl", "pl", "pt")
    private val SISTER_LOCALES = listOf("en", "de", "fr", "pt", "it", "es")

    val ALL: List<CustomPaymentSite> =
        listOf(
            CustomPaymentSite("boat4you", "https://www.boat4you.com", BOAT4YOU_LOCALES, "Boat4You"),
            CustomPaymentSite("catamaran-charter-greece", "https://www.catamaran-charter-greece.com", SISTER_LOCALES, "Catamaran Charter Greece"),
            CustomPaymentSite("catamaran-croatia-charter", "https://www.catamaran-croatia-charter.com", SISTER_LOCALES, "Catamaran Charter Croatia"),
            CustomPaymentSite("catamarancharteritaly", "https://www.catamarancharteritaly.com", SISTER_LOCALES, "Catamaran Charter Italy"),
            CustomPaymentSite("catamaran-charter-caribbean", "https://www.catamaran-charter-caribbean.com", SISTER_LOCALES, "Catamaran Charter Caribbean"),
            CustomPaymentSite("europe-yachts", "https://www.europe-yachts.com", SISTER_LOCALES, "Europe Yachts Charter"),
            CustomPaymentSite("croatia-yachting", "https://www.croatia-yachting.com", SISTER_LOCALES, "Yacht Charter Croatia"),
        )

    private val BY_KEY: Map<String, CustomPaymentSite> = ALL.associateBy { it.key }

    val KEYS: Set<String> = BY_KEY.keys

    fun byKey(key: String?): CustomPaymentSite? = key?.let { BY_KEY[it.trim()] }
}
