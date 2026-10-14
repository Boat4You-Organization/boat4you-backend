package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Path 7 (real-price search, WS-E): the chat quotes the card's own period total - per-day clientPriceEur x the
 * card's numberOfDays - and the card and its link carry the offer's own dates, flagged "nearby" when they are not the
 * requested ones. A local HTTP server plays our public search API.
 */
class AiChatToolPriceTest {
    private val mapper = ObjectMapper()
    private var yachts = "[]"
    private var yachtCalls = 0
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body =
                    when {
                        ex.requestURI.path.startsWith("/public/locations") -> """{"content":[]}"""
                        ex.requestURI.path.startsWith("/public/catalogue") -> "[]"
                        else -> {
                            yachtCalls++
                            """{"content":$yachts,"page":{"totalElements":1}}"""
                        }
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

    private fun row(
        perDay: String,
        nights: Int?,
        from: String?,
        to: String?,
    ): String =
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("slug", "lagoon-42-blue")
                put("name", "Blue")
                put("modelName", "Lagoon 42")
                put("clientPriceEur", perDay.toBigDecimal())
                nights?.let { put("numberOfDays", it) }
                from?.let { put("offerDateFrom", it) }
                to?.let { put("offerDateTo", it) }
            },
        )

    private data class Answer(val model: JsonNode, val card: JsonNode?)

    private fun search(
        rows: List<String>,
        start: String = "2027-07-03",
        end: String = "2027-07-10",
        extra: String = "",
    ): Answer {
        yachts = rows.joinToString(",", "[", "]")
        val out = tools.searchYachts(mapper.readTree("""{"countryCode":"HR","startDate":"$start","endDate":"$end"$extra}"""))
        return Answer(mapper.readTree(out.resultForModel), out.cards?.firstOrNull())
    }

    @Test
    fun `an exact 7-night offer is quoted at 7 x per day with the requested dates`() {
        val a = search(listOf(row("500.00", 7, "2027-07-03", "2027-07-10")))
        val y = a.model.path("yachts")[0]
        y.path("totalPriceEur").asInt() shouldBe 3500
        y.path("dates").asText() shouldBe "exact"
        y.path("nights").asInt() shouldBe 7
        y.path("url").asText() shouldContain "startDate=2027-07-03&endDate=2027-07-10"
        a.card!!.path("totalPriceEur").asInt() shouldBe 3500
        a.card.path("days").asInt() shouldBe 7
        a.card.path("dates").asText() shouldBe "exact"
    }

    @Test
    fun `a shifted offer carries its own dates in the link and the card and is flagged nearby`() {
        val a = search(listOf(row("400.00", 7, "2027-07-10", "2027-07-17")))
        val y = a.model.path("yachts")[0]
        y.path("totalPriceEur").asInt() shouldBe 2800
        y.path("dates").asText() shouldBe "nearby"
        y.path("startDate").asText() shouldBe "2027-07-10"
        y.path("endDate").asText() shouldBe "2027-07-17"
        y.path("url").asText() shouldContain "startDate=2027-07-10&endDate=2027-07-17"
        a.card!!.path("startDate").asText() shouldBe "2027-07-10"
        a.card.path("endDate").asText() shouldBe "2027-07-17"
        a.card.path("dates").asText() shouldBe "nearby"
    }

    @Test
    fun `a tiled 14-night card is quoted at 14 x per day`() {
        val a = search(listOf(row("450.00", 14, "2027-07-03", "2027-07-17")), end = "2027-07-17")
        a.model.path("yachts")[0].path("totalPriceEur").asInt() shouldBe 6300
        a.card!!.path("days").asInt() shouldBe 14
        a.card.path("totalPriceEur").asInt() shouldBe 6300
    }

    @Test
    fun `a 14-night offer shown for a 7-night request is never quoted at half price`() {
        val a = search(listOf(row("300.00", 14, "2027-07-03", "2027-07-17")))
        val y = a.model.path("yachts")[0]
        y.path("totalPriceEur").asInt() shouldBe 4200
        y.path("dates").asText() shouldBe "nearby"
        a.card!!.path("days").asInt() shouldBe 14
    }

    @Test
    fun `a card without numberOfDays or offer dates falls back to the requested period`() {
        val a = search(listOf(row("500.00", null, null, null), row("500.00", 0, null, null)))
        a.model.path("yachts").map { it.path("totalPriceEur").asInt() } shouldBe listOf(3500, 3500)
        a.model.path("yachts")[0].path("dates").asText() shouldBe "exact"
        a.card!!.path("startDate").asText() shouldBe "2027-07-03"
    }

    @Test
    fun `the budget cap uses the corrected total`() {
        // 300 x 14 = 4200 > 4000 (the requested-days figure 2100 would have passed)
        val a = search(listOf(row("300.00", 14, "2027-07-03", "2027-07-17")), extra = ""","maxTotalPriceEur":4000""")
        a.model.path("matches").asInt() shouldBe 0
        a.card shouldBe null
    }

    @Test
    fun `a 2-night request is refused before any search, 3 nights pass`() {
        search(emptyList(), end = "2027-07-05").model.path("error").asText() shouldContain "3 to 28 nights"
        yachtCalls shouldBe 0
        search(emptyList(), end = "2027-07-06").model.has("error") shouldBe false
        yachtCalls shouldBe 1
    }

    @Test
    fun `no agency or owner name ever reaches the model or the card`() {
        yachts = "[" + row("500.00", 7, "2027-07-03", "2027-07-10").dropLast(1) + ""","agencyName":"Secret Charter d.o.o."}]"""
        val out = tools.searchYachts(mapper.readTree("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10"}"""))
        out.resultForModel.contains("Secret") shouldBe false
        mapper.writeValueAsString(out.cards).contains("Secret") shouldBe false
    }
}
