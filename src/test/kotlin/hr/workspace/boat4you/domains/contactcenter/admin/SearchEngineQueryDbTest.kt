package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import hr.workspace.boat4you.domains.contactcenter.search.SearchText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SearchEngineQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = SearchEngineQuery(jdbc)
    private val from = Instant.parse("2026-09-30T22:00:00Z") // 1.10. in Zagreb
    private val to = Instant.parse("2026-10-31T23:00:00Z") // 1.11. 00:00 in Zagreb, exclusive
    private val inside = LocalDate.of(2026, 10, 15)

    @Suppress("LongParameterList")
    private fun row(
        table: String,
        engine: String = "GOOGLE",
        site: String = "boat4you",
        day: LocalDate = inside,
        query: String? = null,
        path: String? = null,
        country: String = "US",
        device: String = "MOBILE",
        clicks: Int,
        impressions: Int,
        position: Double = 5.0,
    ) {
        val text = listOfNotNull(query?.let { "query" to it }, path?.let { "path" to it })
        jdbc.update(
            "INSERT INTO $table (engine, site_id, day, ${text.joinToString("") { "${it.first}, " }}country, device, clicks, impressions, position_sum) " +
                "VALUES (?, ?, ?, ${"?, ".repeat(text.size)}?, ?, ?, ?, ?)",
            *(listOf<Any>(engine, site, Date.valueOf(day)) + text.map { it.second } + listOf(country, device, clicks, impressions, position * impressions))
                .toTypedArray(),
        )
    }

    @Suppress("LongParameterList")
    private fun organic(
        site: String = "boat4you",
        landing: String,
        referrer: String = "https://www.google.com/",
        country: String = "US",
        device: String = "MOBILE",
        inquiry: Boolean = false,
        at: Instant = Instant.parse("2026-10-15T10:00:00Z"),
        channel: String = "ORGANIC",
    ) {
        val (_, visitId) = ccVisit(jdbc, UUID.randomUUID().toString(), site, at)
        jdbc.update(
            "UPDATE cc_visit SET channel = ?, landing_url = ?, referrer = ?, country_code = ?, device_class = ?, started_at = ? WHERE id = ?",
            channel,
            landing,
            referrer,
            country,
            device,
            Timestamp.from(at),
            visitId,
        )
        if (inquiry) jdbc.update("INSERT INTO cc_event (visit_id, type) VALUES (?, 'INQUIRY')", visitId)
    }

    private fun result(
        siteId: String? = null,
        country: String? = null,
        device: String? = null,
    ) = query.searchEngines(from, to, siteId, country, device)

    @Test
    fun `google and bing totals sit next to our organic visits per site`() {
        row("seo_site_day", clicks = 10, impressions = 200, position = 4.0)
        row("seo_site_day", country = "DE", device = "DESKTOP", clicks = 2, impressions = 100, position = 10.0)
        row("seo_site_day", day = LocalDate.of(2026, 11, 2), clicks = 99, impressions = 99) // after the range
        row("seo_site_day", engine = "BING", country = "", device = "", clicks = 3, impressions = 30, position = 0.0)
        row("seo_site_day", site = "europe-yachts", clicks = 1, impressions = 10)
        row("seo_query_day", query = "boat4you", clicks = 4, impressions = 20)
        row("seo_query_day", query = "boat4you", path = "/", clicks = 4, impressions = 20) // per page: not counted again
        organic(landing = "https://www.boat4you.com/en?gclid=x", inquiry = true)
        organic(landing = "https://www.boat4you.com/en/", referrer = "https://www.bing.com/")
        organic(landing = "https://www.boat4you.com/en", referrer = "https://duckduckgo.com/")
        organic(landing = "https://www.boat4you.com/en", channel = "DIRECT")
        jdbc.update("INSERT INTO seo_import_day (engine, site_id, day) VALUES ('GOOGLE', 'boat4you', '2026-11-01'), ('BING', 'boat4you', '2026-10-27')")

        val r = result()
        r.fromDay shouldBe LocalDate.of(2026, 10, 1)
        r.toDay shouldBe LocalDate.of(2026, 10, 31)
        r.lastDay shouldBe mapOf("GOOGLE" to LocalDate.of(2026, 11, 1), "BING" to LocalDate.of(2026, 10, 27))
        r.google shouldBe EngineTotals(13, 310, (800.0 + 1000.0 + 50.0) / 310)
        r.googleClicksWithQuery shouldBe 4
        r.bing shouldBe EngineTotals(3, 30, null)
        r.organicVisits shouldBe 3
        r.organicInquiries shouldBe 1
        r.engines shouldContainExactly listOf(Count("BING", 1), Count("DUCKDUCKGO", 1), Count("GOOGLE", 1))
        r.sites.map { it.siteId } shouldBe SiteIds.ALL
        r.sites.first() shouldBe SiteSearch("boat4you", EngineTotals(12, 300, 1800.0 / 300), EngineTotals(3, 30, null), 3, 1)
    }

    @Test
    fun `a country or device filter narrows google and our visits and leaves bing out`() {
        row("seo_site_day", clicks = 10, impressions = 200)
        row("seo_site_day", country = "DE", clicks = 2, impressions = 100)
        row("seo_site_day", engine = "BING", country = "", device = "", clicks = 3, impressions = 30)
        row("seo_query_day", engine = "BING", query = "yacht", path = "", country = "", device = "", clicks = 1, impressions = 5)
        organic(landing = "https://www.boat4you.com/en", country = "DE")
        organic(landing = "https://www.boat4you.com/en", country = "US")

        val r = result(country = "DE")
        r.google.clicks shouldBe 2
        r.bing.shouldBeNull()
        r.bingQueries.shouldBeEmpty()
        r.organicVisits shouldBe 1
        r.sites.first().bing.shouldBeNull()
        result().bingQueries.map { it.query } shouldBe listOf("yacht")
    }

    @Test
    fun `top queries come from the whole-site rows and list their sites`() {
        row("seo_query_day", query = "lagoon 42", clicks = 4, impressions = 40, position = 3.0)
        row("seo_query_day", query = "lagoon 42", country = "DE", clicks = 0, impressions = 10, position = 8.0)
        row("seo_query_day", site = "europe-yachts", query = "lagoon 42", clicks = 1, impressions = 10, position = 6.0)
        row("seo_query_day", query = "bali 4.2", clicks = 1, impressions = 50)
        row("seo_query_day", query = "lagoon 42", path = "/en/a", clicks = 9, impressions = 90) // per page: never in the top list
        row("seo_query_day", query = "per page only", path = "/en/a", clicks = 20, impressions = 90)

        val q = result().googleQueries
        q.map { it.query } shouldBe listOf("lagoon 42", "bali 4.2")
        q.first() shouldBe QueryStat("lagoon 42", listOf("boat4you", "europe-yachts"), 5, 60, (120.0 + 80.0 + 60.0) / 60)
    }

    @Test
    fun `landing pages join google pages with our organic landing pages`() {
        row("seo_page_day", path = "/en/yachts/lagoon-42", clicks = 6, impressions = 90)
        row("seo_page_day", path = "/", clicks = 2, impressions = 40)
        row("seo_query_day", query = "lagoon 42 charter", path = "/en/yachts/lagoon-42", clicks = 3, impressions = 20)
        row("seo_query_day", query = "lagoon 42", path = "/en/yachts/lagoon-42", clicks = 2, impressions = 30)
        row("seo_query_day", query = "lagoon", path = "/en/yachts/lagoon-42", clicks = 1, impressions = 10)
        row("seo_query_day", query = "lagoon 42 price", path = "/en/yachts/lagoon-42", clicks = 0, impressions = 5)
        organic(landing = "https://www.boat4you.com/en/yachts/lagoon-42/?utm_source=x", inquiry = true)
        organic(landing = "https://www.boat4you.com/en/yachts/lagoon-42")
        organic(landing = "https://www.boat4you.com/de/blog/croatia", referrer = "https://www.bing.com/")

        val pages = result().pages
        pages.map { it.path } shouldBe listOf("/en/yachts/lagoon-42", "/", "/de/blog/croatia")
        pages.first() shouldBe
            LandingPage("boat4you", "/en/yachts/lagoon-42", 6, 90, 5.0, listOf("lagoon 42 charter", "lagoon 42", "lagoon"), 2, 1)
        pages.last() shouldBe LandingPage("boat4you", "/de/blog/croatia", 0, 0, null, emptyList(), 1, 0)
    }

    @Test
    fun `the sql landing path follows the kotlin rule`() {
        listOf(
            "https://www.boat4you.com",
            "https://www.boat4you.com/",
            "https://www.boat4you.com/de/",
            "https://www.boat4you.com/en/yachts/lagoon-42?x=1#top",
            "https://www.boat4you.com//",
            "https://www.boat4you.com/a%20b/?q",
        ).forEach { url ->
            jdbc.queryForObject("SELECT ${SearchEngineQuery.LANDING_PATH} FROM (SELECT ?::text AS landing_url) v", String::class.java, url) shouldBe
                SearchText.path(url)
        }
    }
}
