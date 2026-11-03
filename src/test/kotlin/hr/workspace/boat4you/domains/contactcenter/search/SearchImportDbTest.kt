package hr.workspace.boat4you.domains.contactcenter.search

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.ccTx
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Date
import java.time.LocalDate

class SearchImportDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val today = LocalDate.of(2026, 11, 3)

    private class FakeGoogle(
        override val enabled: Boolean = true,
        val failOn: Pair<String, LocalDate>? = null,
    ) : GoogleSearchApi {
        val calls = mutableListOf<Triple<String, LocalDate, SearchGrain>>()

        override fun rows(
            siteId: String,
            day: LocalDate,
            grain: SearchGrain,
        ): List<SearchRow> {
            if (failOn == siteId to day) error("HTTP 429")
            calls += Triple(siteId, day, grain)
            return when (grain) {
                SearchGrain.SITE -> listOf(SearchRow(country = "US", device = "MOBILE", clicks = 3, impressions = 50, positionSum = 400.0))
                SearchGrain.PAGE -> listOf(SearchRow(path = "/en", country = "US", device = "MOBILE", clicks = 3, impressions = 40, positionSum = 300.0))
                SearchGrain.QUERY -> listOf(SearchRow("lagoon 42", "/en", "US", "MOBILE", 1, 10, 50.0))
            }
        }
    }

    private class FakeBing(
        val byGrain: Map<SearchGrain, Map<LocalDate, List<SearchRow>>>,
    ) : BingSearchApi {
        override val enabled = true
        var siteCalls = 0

        override fun sites(): Map<String, String> {
            siteCalls++
            return mapOf("boat4you" to "https://www.boat4you.com/")
        }

        override fun rows(
            siteUrl: String,
            grain: SearchGrain,
        ) = byGrain[grain].orEmpty()
    }

    private val noBing =
        object : BingSearchApi {
            override val enabled = false

            override fun sites() = error("not configured")

            override fun rows(
                siteUrl: String,
                grain: SearchGrain,
            ) = error("not configured")
        }

    private fun import(
        google: GoogleSearchApi = FakeGoogle(),
        bing: BingSearchApi = noBing,
        days: Int = 3,
    ) = SearchImport(jdbc, google, bing, ccTx(jdbc), days)

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    @Test
    fun `google takes the newest days first, a bounded number per site, and stores every grain`() {
        val google = FakeGoogle()
        val result = import(google).run(today)
        result shouldBe SearchImport.Result(googleDays = 21, bingDays = 0, rows = 63, failures = 0)
        google.calls.filter { it.first == "boat4you" }.map { it.second }.distinct() shouldContainExactly
            listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 30))
        count("seo_site_day") shouldBe 21
        count("seo_page_day") shouldBe 21
        jdbc.queryForObject("SELECT query || ' ' || path || ' ' || country || ' ' || device FROM seo_query_day LIMIT 1", String::class.java) shouldBe
            "lagoon 42 /en US MOBILE"
        // The next run continues the history; the recent days were imported in the last 12 hours.
        import(google).googleDays("boat4you", today) shouldContainExactly
            listOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 27))
    }

    @Test
    fun `an unsettled day comes back after twelve hours and a settled one never`() {
        import().run(today)
        // Imported a day after the day (before Google settled it) and long ago; 30.10. after it settled.
        jdbc.update("UPDATE seo_import_day SET imported_at = day + 1")
        jdbc.update("UPDATE seo_import_day SET imported_at = day + 5 WHERE day = '2026-10-30'")
        import().googleDays("boat4you", today) shouldContainExactly
            listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 29))
    }

    @Test
    fun `a re-import replaces the day instead of adding to it`() {
        import().run(today)
        jdbc.update("UPDATE seo_import_day SET imported_at = day + 1")
        import().run(today)
        jdbc.queryForObject(
            "SELECT sum(clicks) FROM seo_site_day WHERE site_id = 'boat4you' AND day = ?",
            Int::class.java,
            Date.valueOf(LocalDate.of(2026, 11, 1)),
        ) shouldBe 3
    }

    @Test
    fun `a failing site keeps its earlier days and the other sites go on`() {
        val google = FakeGoogle(failOn = "catamaran-greece" to LocalDate.of(2026, 10, 31))
        val result = import(google).run(today)
        result.failures shouldBe 1
        result.googleDays shouldBe 19
        jdbc.queryForObject("SELECT count(*) FROM seo_import_day WHERE site_id = 'catamaran-greece'", Int::class.java) shouldBe 1
    }

    @Test
    fun `nothing runs while google and bing are not configured`() {
        import(FakeGoogle(enabled = false)).run(today) shouldBe SearchImport.Result(0, 0, 0, 0)
        count("seo_import_day") shouldBe 0
    }

    @Test
    fun `bing replaces a grain only on the days it still reports for it, once in twenty hours`() {
        val week = LocalDate.of(2026, 10, 25)
        val day = LocalDate.of(2026, 10, 27)
        jdbc.update(
            "INSERT INTO seo_query_day (engine, site_id, day, query, clicks, impressions, position_sum) VALUES ('BING', 'boat4you', ?, 'old week', 1, 1, 1)",
            Date.valueOf(LocalDate.of(2026, 4, 5)),
        )
        val bing =
            FakeBing(
                mapOf(
                    SearchGrain.SITE to mapOf(day to listOf(SearchRow(clicks = 4, impressions = 70, positionSum = 0.0))),
                    SearchGrain.QUERY to mapOf(week to listOf(SearchRow(query = "boat4you", clicks = 2, impressions = 5, positionSum = 10.0))),
                ),
            )
        val result = import(FakeGoogle(enabled = false), bing).run(today)
        result shouldBe SearchImport.Result(0, 2, 2, 0)
        jdbc.queryForList("SELECT query FROM seo_query_day WHERE engine = 'BING' ORDER BY day", String::class.java) shouldContainExactly
            listOf("old week", "boat4you")
        count("seo_site_day") shouldBe 1
        import(FakeGoogle(enabled = false), bing).run(today) shouldBe SearchImport.Result(0, 0, 0, 0)
        bing.siteCalls shouldBe 1
    }

    @Test
    fun `every site of the list is imported`() {
        import(days = 1).run(today)
        jdbc.queryForList("SELECT DISTINCT site_id FROM seo_import_day ORDER BY 1", String::class.java) shouldBe SiteIds.ALL.sorted()
    }
}
