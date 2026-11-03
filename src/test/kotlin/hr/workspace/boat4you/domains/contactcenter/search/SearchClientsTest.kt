package hr.workspace.boat4you.domains.contactcenter.search

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The parsing of both engines' rows and the shared path and country rules. */
class SearchClientsTest {
    private val mapper = ObjectMapper()

    @Test
    fun `a page url becomes the path the admin compares with landing urls`() {
        SearchText.path("https://www.boat4you.com/") shouldBe "/"
        SearchText.path("https://www.boat4you.com") shouldBe "/"
        SearchText.path("https://www.boat4you.com/de/") shouldBe "/de"
        SearchText.path("https://www.boat4you.com/en/yachts/lagoon-42?x=1#top") shouldBe "/en/yachts/lagoon-42"
        SearchText.path("https://www.boat4you.com//") shouldBe "/"
        SearchText.path("https://www.boat4you.com/a%20b/") shouldBe "/a%20b"
    }

    @Test
    fun `google alpha-3 countries become alpha-2 and unknown is empty`() {
        SearchText.country("usa") shouldBe "US"
        SearchText.country("deu") shouldBe "DE"
        SearchText.country("hrv") shouldBe "HR"
        SearchText.country("zzz") shouldBe ""
        SearchText.country("") shouldBe ""
    }

    @Test
    fun `a search analytics row follows its dimensions`() {
        val node =
            mapper.readTree(
                """{"keys":["catamaran charter croatia","https://www.catamaran-croatia-charter.com/catamarans/","usa","MOBILE"],
                   "clicks":2,"impressions":6,"ctr":0.33,"position":2.5}""",
            )
        SearchConsoleClient.row(node, SearchConsoleClient.DIMENSIONS.getValue(SearchGrain.QUERY)) shouldBe
            SearchRow("catamaran charter croatia", "/catamarans", "US", "MOBILE", 2, 6, 15.0)
        val site = mapper.readTree("""{"keys":["deu","DESKTOP"],"clicks":0,"impressions":3,"position":11.0}""")
        SearchConsoleClient.row(site, SearchConsoleClient.DIMENSIONS.getValue(SearchGrain.SITE)) shouldBe
            SearchRow(country = "DE", device = "DESKTOP", clicks = 0, impressions = 3, positionSum = 33.0)
    }

    @Test
    fun `the property of a site is its www url prefix`() {
        SearchConsoleClient.siteUrl("catamaran-italy") shouldBe "https://www.catamarancharteritaly.com/"
    }

    @Test
    fun `a wcf date is the calendar day of its own offset`() {
        BingWebmasterClient.day("/Date(1316156400000-0700)/") shouldBe LocalDate.of(2011, 9, 16)
        BingWebmasterClient.day("/Date(1761868800000)/") shouldBe LocalDate.of(2025, 10, 31)
        BingWebmasterClient.day("2025-10-31") shouldBe null
    }

    @Test
    fun `bing rows carry the query or the page and no unknown position`() {
        val query = mapper.readTree("""{"Query":"yacht charter croatia","Clicks":3,"Impressions":40,"AvgImpressionPosition":7.5,"Date":"/Date(0)/"}""")
        BingWebmasterClient.row(query, SearchGrain.QUERY) shouldBe SearchRow(query = "yacht charter croatia", clicks = 3, impressions = 40, positionSum = 300.0)
        val page = mapper.readTree("""{"Query":"https://www.europe-yachts.com/en/greece/","Clicks":1,"Impressions":9,"AvgImpressionPosition":-1}""")
        BingWebmasterClient.row(page, SearchGrain.PAGE) shouldBe SearchRow(path = "/en/greece", clicks = 1, impressions = 9, positionSum = 0.0)
        val traffic = mapper.readTree("""{"Clicks":5,"Impressions":120,"Date":"/Date(0)/"}""")
        BingWebmasterClient.row(traffic, SearchGrain.SITE) shouldBe SearchRow(clicks = 5, impressions = 120, positionSum = 0.0)
    }
}
