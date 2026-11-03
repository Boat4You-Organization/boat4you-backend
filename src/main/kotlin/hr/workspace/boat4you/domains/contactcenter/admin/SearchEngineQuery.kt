package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.search.SearchGrain
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Clicks and impressions of a range; [position] is the impression-weighted average, null when no impression has one. */
data class EngineTotals(
    val clicks: Long,
    val impressions: Long,
    val position: Double?,
)

data class SiteSearch(
    val siteId: String,
    val google: EngineTotals,
    val bing: EngineTotals?,
    val organicVisits: Long,
    val organicInquiries: Long,
)

data class QueryStat(
    val query: String,
    val siteIds: List<String>,
    val clicks: Long,
    val impressions: Long,
    val position: Double?,
)

/** A landing page: Google's numbers and [queries] (top three by clicks) next to our organic visits that started there. */
data class LandingPage(
    val siteId: String,
    val path: String,
    val clicks: Long,
    val impressions: Long,
    val position: Double?,
    val queries: List<String>,
    val visits: Long,
    val inquiries: Long,
)

/**
 * [fromDay]..[toDay] are the engine days of the range (Zagreb dates). [lastDay] is the newest imported day per engine.
 * [googleClicksWithQuery] are the Google clicks whose query Google shows; it hides the rest. Bing has no country or device, so
 * [bing] and the Bing parts are null or empty while such a filter is set. Organic visits and inquiries are our tracker's
 * (visitors who consented), an inquiry counted on the visit that came from the search engine.
 */
data class CcSearchEngines(
    val from: Instant,
    val to: Instant,
    val siteId: String?,
    val country: String?,
    val device: String?,
    val fromDay: LocalDate,
    val toDay: LocalDate,
    val lastDay: Map<String, LocalDate>,
    val google: EngineTotals,
    val googleClicksWithQuery: Long,
    val bing: EngineTotals?,
    val organicVisits: Long,
    val organicInquiries: Long,
    val engines: List<Count>,
    val sites: List<SiteSearch>,
    val googleQueries: List<QueryStat>,
    val bingQueries: List<QueryStat>,
    val pages: List<LandingPage>,
)

/** The search engine section of Statistics (F1): V9_118 next to `cc_visit` ORGANIC visits; bots and staff are never counted. */
@Service
class SearchEngineQuery(
    private val jdbc: JdbcTemplate,
) {
    private data class Sums(
        val clicks: Long,
        val impressions: Long,
        val positionSum: Double,
        val positionImpressions: Long,
    ) {
        operator fun plus(o: Sums) = Sums(clicks + o.clicks, impressions + o.impressions, positionSum + o.positionSum, positionImpressions + o.positionImpressions)

        fun totals() = EngineTotals(clicks, impressions, if (positionImpressions > 0) positionSum / positionImpressions else null)
    }

    /** The WHERE of one engine's rows in the range, and its arguments. */
    private class Seo(
        engine: String,
        fromDay: LocalDate,
        toDay: LocalDate,
        siteId: String?,
        country: String?,
        device: String?,
    ) {
        private val filters = listOfNotNull(siteId?.let { "site_id" to it }, country?.let { "country" to it }, device?.let { "device" to it })
        val where = "engine = ? AND day BETWEEN ? AND ?" + filters.joinToString("") { " AND ${it.first} = ?" }
        val args: List<Any> = listOf(engine, Date.valueOf(fromDay), Date.valueOf(toDay)) + filters.map { it.second }
    }

    private data class Organic(
        val siteId: String,
        val engine: String,
        val path: String,
        val visits: Long,
        val inquiries: Long,
    )

    fun searchEngines(
        from: Instant,
        to: Instant,
        siteId: String?,
        country: String?,
        device: String?,
    ): CcSearchEngines {
        val fromDay = from.atZone(ZONE).toLocalDate()
        val toDay = to.minusMillis(1).atZone(ZONE).toLocalDate()
        val google = Seo("GOOGLE", fromDay, toDay, siteId, country, device)
        val bing = if (country == null && device == null) Seo("BING", fromDay, toDay, siteId, null, null) else null
        val googleSites = sumsBySite(google)
        val bingSites = bing?.let(::sumsBySite).orEmpty()
        val organic = organic(from, to, siteId, country, device)
        val sites =
            SiteIds.ALL.filter { siteId == null || it == siteId }.map { s ->
                val own = organic.filter { it.siteId == s }
                SiteSearch(
                    siteId = s,
                    google = (googleSites[s] ?: NONE).totals(),
                    bing = bing?.let { (bingSites[s] ?: NONE).totals() },
                    organicVisits = own.sumOf { it.visits },
                    organicInquiries = own.sumOf { it.inquiries },
                )
            }
        return CcSearchEngines(
            from = from,
            to = to,
            siteId = siteId,
            country = country,
            device = device,
            fromDay = fromDay,
            toDay = toDay,
            lastDay = lastDays(),
            google = googleSites.values.fold(NONE, Sums::plus).totals(),
            googleClicksWithQuery =
                jdbc.queryForObject("SELECT COALESCE(sum(clicks), 0) FROM seo_query_day WHERE ${google.where}", Long::class.java, *google.args.toTypedArray())!!,
            bing = bing?.let { bingSites.values.fold(NONE, Sums::plus).totals() },
            organicVisits = organic.sumOf { it.visits },
            organicInquiries = organic.sumOf { it.inquiries },
            engines =
                organic
                    .groupBy { it.engine }
                    .map { (k, rows) -> Count(k, rows.sumOf { it.visits }) }
                    .sortedWith(compareByDescending<Count> { it.value }.thenBy { it.key }),
            sites = sites,
            googleQueries = queries(google),
            bingQueries = bing?.let(::queries).orEmpty(),
            pages = pages(google, organic),
        )
    }

    private fun lastDays(): Map<String, LocalDate> =
        jdbc
            .query("SELECT engine, max(day) AS d FROM seo_import_day GROUP BY engine") { rs, _ -> rs.getString("engine") to rs.getDate("d").toLocalDate() }
            .toMap()

    private fun sumsBySite(seo: Seo): Map<String, Sums> =
        jdbc
            .query(
                "SELECT site_id, $SUMS FROM ${SearchGrain.SITE.table} WHERE ${seo.where} GROUP BY site_id",
                { rs, _ -> rs.getString("site_id") to sums(rs) },
                *seo.args.toTypedArray(),
            ).toMap()

    private fun queries(seo: Seo): List<QueryStat> =
        jdbc.query(
            "SELECT query, array_agg(DISTINCT site_id ORDER BY site_id) AS sites, $SUMS FROM ${SearchGrain.QUERY.table} WHERE ${seo.where} " +
                "GROUP BY query ORDER BY sum(clicks) DESC, sum(impressions) DESC, query LIMIT $TOP_QUERIES",
            { rs, _ ->
                val s = sums(rs).totals()
                @Suppress("UNCHECKED_CAST")
                QueryStat(rs.getString("query"), (rs.getArray("sites").array as Array<String>).toList(), s.clicks, s.impressions, s.position)
            },
            *seo.args.toTypedArray(),
        )

    /**
     * The landing pages: Google's top pages by clicks and our top organic landing pages by visits, Google's numbers and top
     * three queries for each, sorted by clicks and then visits.
     */
    private fun pages(
        google: Seo,
        organic: List<Organic>,
    ): List<LandingPage> {
        val ours = organic.groupBy { it.siteId to it.path }.mapValues { (_, rows) -> rows.sumOf { it.visits } to rows.sumOf { it.inquiries } }
        val top =
            jdbc.query(
                "SELECT site_id, path FROM ${SearchGrain.PAGE.table} WHERE ${google.where} GROUP BY 1, 2 " +
                    "ORDER BY sum(clicks) DESC, sum(impressions) DESC, 2, 1 LIMIT $TOP_PAGES",
                { rs, _ -> rs.getString("site_id") to rs.getString("path") },
                *google.args.toTypedArray(),
            )
        val pairs = (top + ours.entries.sortedByDescending { it.value.first }.take(TOP_PAGES).map { it.key }).distinct()
        if (pairs.isEmpty()) return emptyList()
        val inPairs = " AND (site_id, path) IN (${pairs.joinToString(", ") { "(?, ?)" }})"
        val pairArgs = pairs.flatMap { listOf(it.first, it.second) }
        val pageSums =
            jdbc
                .query(
                    "SELECT site_id, path, $SUMS FROM ${SearchGrain.PAGE.table} WHERE ${google.where}$inPairs GROUP BY 1, 2",
                    { rs, _ -> (rs.getString("site_id") to rs.getString("path")) to sums(rs) },
                    *(google.args + pairArgs).toTypedArray(),
                ).toMap()
        val pageQueries =
            jdbc
                .query(
                    "SELECT site_id, path, query FROM (SELECT site_id, path, query, row_number() OVER (PARTITION BY site_id, path " +
                        "ORDER BY sum(clicks) DESC, sum(impressions) DESC, query) AS rn FROM ${SearchGrain.QUERY.table} WHERE ${google.where}$inPairs " +
                        "GROUP BY 1, 2, 3) x WHERE rn <= $TOP_PAGE_QUERIES ORDER BY site_id, path, rn",
                    { rs, _ -> (rs.getString("site_id") to rs.getString("path")) to rs.getString("query") },
                    *(google.args + pairArgs).toTypedArray(),
                ).groupBy({ it.first }, { it.second })
        return pairs
            .map { key ->
                val s = (pageSums[key] ?: NONE).totals()
                val (visits, inquiries) = ours[key] ?: (0L to 0L)
                LandingPage(key.first, key.second, s.clicks, s.impressions, s.position, pageQueries[key].orEmpty(), visits, inquiries)
            }.sortedWith(compareByDescending<LandingPage> { it.clicks }.thenByDescending { it.visits }.thenBy { it.path }.thenBy { it.siteId })
    }

    private fun organic(
        from: Instant,
        to: Instant,
        siteId: String?,
        country: String?,
        device: String?,
    ): List<Organic> {
        val filters = listOfNotNull(siteId?.let { "v.site_id" to it }, country?.let { "v.country_code" to it }, device?.let { "v.device_class" to it })
        return jdbc.query(
            "WITH ov AS (SELECT v.id, v.site_id, lower(substring(v.referrer FROM '^[a-zA-Z]+://([^/:?#]+)')) AS host, $LANDING_PATH AS path " +
                "FROM cc_visit v JOIN cc_visitor vr ON vr.id = v.visitor_id WHERE v.started_at >= ? AND v.started_at < ? AND v.channel = 'ORGANIC' " +
                "AND v.landing_url IS NOT NULL AND NOT v.is_bot AND NOT vr.is_staff" + filters.joinToString("") { " AND ${it.first} = ?" } + ") " +
                "SELECT site_id, $ENGINE AS engine, path, count(*) AS visits, " +
                "count(*) FILTER (WHERE EXISTS (SELECT 1 FROM cc_event e WHERE e.visit_id = ov.id AND e.type = 'INQUIRY')) AS inquiries " +
                "FROM ov GROUP BY 1, 2, 3",
            { rs, _ -> Organic(rs.getString("site_id"), rs.getString("engine"), rs.getString("path"), rs.getLong("visits"), rs.getLong("inquiries")) },
            Timestamp.from(from),
            Timestamp.from(to),
            *filters.map { it.second }.toTypedArray(),
        )
    }

    private fun sums(rs: ResultSet) = Sums(rs.getLong("c"), rs.getLong("i"), rs.getDouble("ps"), rs.getLong("pi"))

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Zagreb")
        private val NONE = Sums(0, 0, 0.0, 0)
        private const val TOP_QUERIES = 50
        private const val TOP_PAGES = 30
        private const val TOP_PAGE_QUERIES = 3
        private const val SUMS =
            "sum(clicks) AS c, sum(impressions) AS i, COALESCE(sum(position_sum) FILTER (WHERE position_sum > 0), 0) AS ps, " +
                "COALESCE(sum(impressions) FILTER (WHERE position_sum > 0), 0) AS pi"

        /** The path of `v.landing_url` by the rule of [hr.workspace.boat4you.domains.contactcenter.search.SearchText.path]. */
        const val LANDING_PATH =
            "COALESCE(NULLIF(regexp_replace(regexp_replace(v.landing_url, '^[a-zA-Z]+://[^/?#]*|[?#].*$', '', 'g'), '(.)/+$', '\\1'), ''), '/')"

        /** The search engine of an ORGANIC visit by its referrer host (`host` of the CTE). */
        private const val ENGINE =
            "CASE WHEN host ~ '(^|\\.)google\\.' THEN 'GOOGLE' WHEN host ~ '(^|\\.)bing\\.com$' THEN 'BING' " +
                "WHEN host ~ '(^|\\.)duckduckgo\\.com$' THEN 'DUCKDUCKGO' WHEN host ~ '(^|\\.)yahoo\\.' THEN 'YAHOO' " +
                "WHEN host ~ '(^|\\.)ecosia\\.org$' THEN 'ECOSIA' WHEN host ~ '(^|\\.)yandex\\.' THEN 'YANDEX' ELSE 'OTHER' END"
    }
}
