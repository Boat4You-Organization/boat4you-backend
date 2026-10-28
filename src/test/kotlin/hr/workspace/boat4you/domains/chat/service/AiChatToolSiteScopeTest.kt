package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.branding.AiSiteProfile
import hr.workspace.boat4you.domains.branding.BrandRegistry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The fleet of the site a conversation runs on is enforced here, on the server, not by the prompt (BACKEND-11/12,
 * SISTER-1): a country or place outside it is refused without a search, a vessel type is clamped, rows outside the
 * fleet are dropped, and a sister site's rows and CURRENT PAGE link to the sister page and to boat4you's booking
 * (SISTER-18). boat4you itself stays exactly as it is (BACKEND-14, BACKEND-15). A local HTTP server plays our public API.
 */
class AiChatToolSiteScopeTest {
    private val mapper = ObjectMapper()
    private val seen = mutableListOf<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                seen += ex.requestURI.rawPath + "?" + (ex.requestURI.rawQuery ?: "")
                val placeName =
                    (ex.requestURI.rawQuery ?: "")
                        .split('&')
                        .map { it.split('=', limit = 2) }
                        .firstOrNull { it[0] == "name" }
                        ?.getOrNull(1)
                        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                val body =
                    when {
                        path == "/public/locations" ->
                            when (placeName) {
                                "Split" -> """{"content":[{"id":"r-12","name":"Split","locationType":"REGION","countryCode":"HR"}]}"""
                                "Lefkas" -> """{"content":[{"id":"r-30","name":"Lefkas","locationType":"REGION","countryCode":"GR"}]}"""
                                else -> """{"content":[]}"""
                            }
                        path.startsWith("/public/catalogue") -> "[]"
                        path == "/public/yachts" -> YACHTS
                        path == "/public/yachts/lagoon-42-a" -> DETAIL
                        path == "/public/yachts/inq-1" -> DETAIL.replace("""{"name":"A",""", """{"inquireOnly":true,"name":"A",""")
                        path == "/public/yachts/nb-1" -> DETAIL.replace("""{"name":"A",""", """{"hasBookableFutureOffer":false,"name":"A",""")
                        path == "/public/yachts/hr-1" -> DETAIL.replace(""""countryCode":"GR"""", """"countryCode":"HR"""")
                        else -> "{}"
                    }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val tools = AiChatToolExecutor("http://127.0.0.1:${server.address.port}", mapper)

    @AfterEach
    fun stop() = server.stop(0)

    private fun yachtsQuery(): Map<String, List<String>> =
        seen
            .last { it.startsWith("/public/yachts?") }
            .substringAfter('?')
            .split('&')
            .map { it.split('=', limit = 2) }
            .groupBy({ URLDecoder.decode(it[0], StandardCharsets.UTF_8) }, { URLDecoder.decode(it.getOrElse(1) { "" }, StandardCharsets.UTF_8) })

    private val gr = BrandRegistry().get("catamaran-greece").ai
    private val ey = BrandRegistry().get("europe-yachts").ai
    private val dates = """"startDate":"2027-07-03","endDate":"2027-07-10""""

    private fun search(
        input: String,
        site: AiSiteProfile = AiSiteProfile.BOAT4YOU,
        locale: String = "en",
    ) = tools.searchYachts(mapper.readTree(input), site, locale)

    @Test
    fun `a country outside the site's fleet is refused before any call`() {
        val out = search("""{"countryCode":"HR",$dates}""", gr)
        mapper.readTree(out.resultForModel).path("error").asText() shouldBe "outside this site's fleet"
        seen shouldHaveSize 0
        out.search!!.let {
            it.countryCode shouldBe "HR"
            it.resultCount shouldBe 0
        } // still recorded (BACKEND-17)
    }

    @Test
    fun `Split on the Greece site is refused, Lefkas searches its region`() {
        mapper.readTree(search("""{"countryCode":"GR","location":"Split",$dates}""", gr).resultForModel).path("error").asText() shouldBe
            "'Split' is in HR, outside this site's fleet"
        seen.none { it.startsWith("/public/yachts") } shouldBe true
        search("""{"countryCode":"GR","location":"Lefkas",$dates}""", gr)
        yachtsQuery()["did"] shouldBe listOf("r-30")
        yachtsQuery().containsKey("countryCodes") shouldBe false
    }

    @Test
    fun `a place unknown everywhere still searches the whole country (today's honesty note)`() {
        val out = mapper.readTree(search("""{"countryCode":"GR","location":"Atlantis",$dates}""", gr).resultForModel)
        yachtsQuery()["countryCodes"] shouldBe listOf("GR")
        out.path("searchedArea").asText() shouldBe "place 'Atlantis' not found — searched the whole country; tell the visitor honestly"
    }

    @Test
    fun `a sailing yacht on a catamaran site is clamped and other rows are dropped`() {
        val out = search("""{"countryCode":"GR","vesselType":"SAILING_YACHT",$dates}""", gr, "de")
        yachtsQuery()["vesselType"] shouldBe listOf("CATAMARAN", "POWER_CATAMARAN")
        val model = mapper.readTree(out.resultForModel)
        model.path("vesselNote").asText() shouldBe "SAILING_YACHT is not offered on this site; searched catamarans and power catamarans instead"
        model.path("yachts").map { it.path("url").asText() } shouldBe
            listOf("https://www.catamaran-charter-greece.com/de/catamarans/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10")
        model.path("yachts")[0].path("bookUrl").asText() shouldBe
            "https://www.boat4you.com/de/boat/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10&currency=EUR&cc_src=catamaran-greece"
        out.cards!!.map { it.path("url").asText() to it.path("bookUrl").asText() } shouldBe
            listOf(
                "https://www.catamaran-charter-greece.com/de/catamarans/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10" to
                    "https://www.boat4you.com/de/boat/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10&currency=EUR&cc_src=catamaran-greece",
            )
        out.search!!.vesselType shouldBe "CATAMARAN,POWER_CATAMARAN" // what was sent, not what the model asked
    }

    @Test
    fun `boat4you is unchanged - every row, today's link, no bookUrl, cards without new keys`() {
        val out = search("""{"countryCode":"HR","location":"Split",$dates}""")
        yachtsQuery()["did"] shouldBe listOf("r-12")
        val rows = mapper.readTree(out.resultForModel).path("yachts")
        rows.size() shouldBe 3
        rows[0].path("url").asText() shouldBe "https://www.boat4you.com/boat/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10&currency=EUR"
        rows[0].has("bookUrl") shouldBe false
        out.cards!!.none { it.has("url") || it.has("bookUrl") } shouldBe true // the widget builds the boat4you link from slug (WEB-7)
    }

    @Test
    fun `Europe Yachts takes every type but only its five countries`() {
        search("""{"countryCode":"TR","vesselType":"SAILING_YACHT",$dates}""", ey)
        yachtsQuery()["vesselType"] shouldBe listOf("SAILING_YACHT")
        mapper.readTree(search("""{"countryCode":"FR",$dates}""", ey).resultForModel).path("error").asText() shouldBe "outside this site's fleet"
    }

    @Test
    fun `CURRENT PAGE on a sister boat page links that page and the boat4you booking`() {
        val ctx = tools.pageContext("/de/catamarans/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10", gr, "de")!!
        ctx shouldContain "- Page URL: https://www.catamaran-charter-greece.com/de/catamarans/lagoon-42-a"
        ctx shouldContain
            "- Book online on boat4you: https://www.boat4you.com/de/boat/lagoon-42-a?startDate=2027-07-03&endDate=2027-07-10&currency=EUR&cc_src=catamaran-greece"
        tools.pageContext("/boat/lagoon-42-a")!! shouldContain "- Page URL: https://www.boat4you.com/boat/lagoon-42-a"
        tools.pageContext("/boat/lagoon-42-a")!! shouldNotContain "Book online on boat4you"
    }

    @Test
    fun `the CURRENT PAGE block is empty for a boat outside the site's fleet, inquiry-only boats get no booking`() {
        tools.pageContext("/catamarans/hr-1", gr, "en").shouldBeNull()
        tools.pageContext("/catamarans/lagoon-42-a", BrandRegistry().get("catamaran-croatia").ai, "en").shouldBeNull()
        val inq = tools.pageContext("/catamarans/inq-1", gr, "en")!!
        inq shouldContain "- Booking: on request only - offer the visitor an inquiry, never send them to book online"
        inq shouldNotContain "Book online on boat4you"
    }

    @Test
    fun `a boat without a bookable future offer is inquiry-only too, on a sister site and on boat4you (SISTER-18)`() {
        val sister = tools.pageContext("/catamarans/nb-1", gr, "en")!!
        sister shouldContain "- Booking: on request only - offer the visitor an inquiry, never send them to book online"
        sister shouldContain "- Page URL: https://www.catamaran-charter-greece.com/catamarans/nb-1"
        sister shouldNotContain "Book online on boat4you"
        val b4y = tools.pageContext("/boat/nb-1")!!
        b4y shouldContain "- Booking: on request only - offer the visitor an inquiry, never send them to book online"
        b4y shouldContain "- Page URL: https://www.boat4you.com/boat/nb-1"
    }

    private companion object {
        const val YACHTS =
            """{"content":[
 {"slug":"lagoon-42-a","name":"A","modelName":"Lagoon 42","vesselType":"CATAMARAN","clientPriceEur":500,"numberOfDays":7,"offerDateFrom":"2027-07-03","offerDateTo":"2027-07-10","location":{"name":"Lefkas","countryCode":"GR"}},
 {"slug":"bavaria-46-b","name":"B","modelName":"Bavaria 46","vesselType":"SAILING_YACHT","clientPriceEur":300,"numberOfDays":7,"offerDateFrom":"2027-07-03","offerDateTo":"2027-07-10","location":{"name":"Lefkas","countryCode":"GR"}},
 {"slug":"lagoon-40-c","name":"C","modelName":"Lagoon 40","vesselType":"CATAMARAN","clientPriceEur":400,"numberOfDays":7,"offerDateFrom":"2027-07-03","offerDateTo":"2027-07-10","location":{"name":"Split","countryCode":"HR"}}
],"page":{"totalElements":3}}"""

        const val DETAIL = """{"name":"A","modelName":"Lagoon 42","vesselType":"CATAMARAN","location":{"name":"Lefkas","countryCode":"GR"}}"""
    }
}
