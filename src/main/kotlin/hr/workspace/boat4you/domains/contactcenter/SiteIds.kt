package hr.workspace.boat4you.domains.contactcenter

import java.util.Locale

/**
 * The seven public sites that talk to this backend, by the stable id stored in `site_id` columns
 * (VARCHAR(40)) and sent in the `X-Boat4You-Brand` header.
 *
 * [fromHost] maps a request host to a site id by exact match on the apex host: no suffix or
 * `contains` matching, so look-alike hosts and other subdomains are no site.
 */
object SiteIds {
    const val BOAT4YOU = "boat4you"
    const val CATAMARAN_CROATIA = "catamaran-croatia"
    const val CATAMARAN_GREECE = "catamaran-greece"
    const val CATAMARAN_ITALY = "catamaran-italy"
    const val CATAMARAN_CARIBBEAN = "catamaran-caribbean"
    const val EUROPE_YACHTS = "europe-yachts"
    const val CROATIA_YACHTING = "croatia-yachting"

    val ALL: List<String> =
        listOf(BOAT4YOU, CATAMARAN_CROATIA, CATAMARAN_GREECE, CATAMARAN_ITALY, CATAMARAN_CARIBBEAN, EUROPE_YACHTS, CROATIA_YACHTING)

    /** Apex host (no `www.`) to site id. */
    val HOSTS: Map<String, String> =
        linkedMapOf(
            "boat4you.com" to BOAT4YOU,
            "catamaran-croatia-charter.com" to CATAMARAN_CROATIA,
            "catamaran-charter-greece.com" to CATAMARAN_GREECE,
            "catamarancharteritaly.com" to CATAMARAN_ITALY,
            "catamaran-charter-caribbean.com" to CATAMARAN_CARIBBEAN,
            "europe-yachts.com" to EUROPE_YACHTS,
            "croatia-yachting.com" to CROATIA_YACHTING,
        )

    /** Site id for a host such as `www.Boat4You.com:443`, or null when the host is not one of the seven sites. */
    fun fromHost(host: String): String? =
        HOSTS[
            host
                .trim()
                .lowercase(Locale.ROOT)
                .substringBefore(':')
                .removeSuffix(".")
                .removePrefix("www."),
        ]

    /** True only for the exact, case-sensitive ids in [ALL]. */
    fun isKnown(id: String): Boolean = id in ALL
}
