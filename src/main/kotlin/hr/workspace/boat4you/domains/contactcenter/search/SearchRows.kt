package hr.workspace.boat4you.domains.contactcenter.search

import java.time.LocalDate
import java.util.Locale

/** The three tables of V9_119, one per level of detail an engine reports. */
enum class SearchGrain(
    val table: String,
) {
    SITE("seo_site_day"),
    PAGE("seo_page_day"),
    QUERY("seo_query_day"),
}

/** One row of an engine report for one site and day; [query], [path], [country] and [device] are '' when the grain or engine has none. */
data class SearchRow(
    val query: String = "",
    val path: String = "",
    val country: String = "",
    val device: String = "",
    val clicks: Int,
    val impressions: Int,
    /** Average position × impressions (0 when the engine gives no position). */
    val positionSum: Double,
)

/** Google Search Console, read with the service account of `seo.google-key-file`. */
interface GoogleSearchApi {
    val enabled: Boolean

    /** All rows of [grain] for [siteId] on [day], a day as Google counts it (Pacific time). */
    fun rows(
        siteId: String,
        day: LocalDate,
        grain: SearchGrain,
    ): List<SearchRow>
}

/** Bing Webmaster Tools, read with the API key of `seo.bing-api-key`. */
interface BingSearchApi {
    val enabled: Boolean

    /** The verified sites of the account: site id to the site URL as Bing knows it. */
    fun sites(): Map<String, String>

    /** Every row Bing still keeps for [siteUrl] and [grain], by day (query and page rows are dated by week). */
    fun rows(
        siteUrl: String,
        grain: SearchGrain,
    ): Map<LocalDate, List<SearchRow>>
}

object SearchText {
    const val QUERY_MAX = 500
    const val PATH_MAX = 1000

    /**
     * The path of a page URL as the admin compares it with `cc_visit.landing_url`: no scheme, host, query or fragment, no
     * trailing slash except the root. The same rule as [SearchEngineQuery.LANDING_PATH] in SQL.
     */
    fun path(url: String): String {
        val rest = url.substringAfter("://", url)
        val path = "/" + rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return (if (path.length > 1) path.trimEnd('/').ifEmpty { "/" } else path).take(PATH_MAX)
    }

    private val ALPHA2: Map<String, String> = Locale.getISOCountries().associateBy { Locale.of("", it).isO3Country.lowercase(Locale.ROOT) }

    /** Google's lower-case ISO alpha-3 country (`usa`) as the alpha-2 of `cc_visit.country_code` (`US`); '' when unknown (`zzz`). */
    fun country(alpha3: String): String = ALPHA2[alpha3.lowercase(Locale.ROOT)].orEmpty()
}
