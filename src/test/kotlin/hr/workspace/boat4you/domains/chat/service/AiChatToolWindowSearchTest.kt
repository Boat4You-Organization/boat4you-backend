package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A visitor flexible across several weeks (Nihad 30.10.2026: "any week Sat to Sat between 18 September and 23 October")
 * gets ONE search_yachts call with a window: the server searches every Saturday week in it and merges the boats, each at
 * its cheapest week. A local HTTP server plays our public search API and answers per requested start date.
 */
class AiChatToolWindowSearchTest {
    private val mapper = ObjectMapper()
    private val startDates = CopyOnWriteArrayList<String>()

    /** Rows per week start date; a week not listed has no boats. */
    private val weeks =
        mapOf(
            "2027-09-18" to listOf(row("a", "Bavaria 50 A", 1700, "2027-09-18"), row("b", "Bavaria 50 B", 1600, "2027-09-18")),
            "2027-09-25" to listOf(row("a", "Bavaria 50 A", 1500, "2027-09-25"), row("c", "Oceanis 51 C", 1750, "2027-09-25")),
        )
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val query = ex.requestURI.query.orEmpty().split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
                val body =
                    when {
                        ex.requestURI.path.startsWith("/public/catalogue") -> "[]"
                        else -> {
                            val start = query["startDate"].orEmpty()
                            startDates += start
                            val rows = weeks[start].orEmpty()
                            """{"content":[${rows.joinToString(",")}],"page":{"totalElements":${rows.size}}}"""
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
        slug: String,
        label: String,
        total: Int,
        from: String,
    ): String =
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("slug", slug)
                put("name", label.substringAfterLast(' '))
                put("modelName", label.substringBeforeLast(' '))
                put("clientPriceEur", (total / 7.0).toBigDecimal())
                put("numberOfDays", 7)
                put("offerDateFrom", from)
                put("offerDateTo", LocalDate.parse(from).plusDays(7).toString())
                put("cabins", 5)
            },
        )

    private fun window(
        from: String,
        to: String,
        extra: String = "",
    ) = tools.searchYachts(mapper.readTree("""{"countryCode":"HR","windowStart":"$from","windowEnd":"$to","minCabins":5$extra}"""))

    @Test
    fun `a window searches every Saturday week once and merges each boat at its cheapest week`() {
        val outcome = window("2027-09-18", "2027-10-23", ""","maxTotalPriceEur":1800""")
        startDates shouldContainExactly listOf("2027-09-18", "2027-09-25", "2027-10-02", "2027-10-09", "2027-10-16")
        val result = mapper.readTree(outcome.resultForModel)
        result.path("yachts").map { it.path("yacht").asText() to it.path("totalPriceEur").asInt() } shouldContainExactly
            listOf("Bavaria 50 A" to 1500, "Bavaria 50 B" to 1600, "Oceanis 51 C" to 1750)
        result.path("yachts").map { it.path("startDate").asText() } shouldContainExactly listOf("2027-09-25", "2027-09-18", "2027-09-25")
        result.path("weeks").map { it.path("startDate").asText() to it.path("matches").asInt() } shouldContainExactly
            listOf("2027-09-18" to 2, "2027-09-25" to 2, "2027-10-02" to 0, "2027-10-09" to 0, "2027-10-16" to 0)
        outcome.cards!!.map { it.path("slug").asText() to it.path("startDate").asText() } shouldContainExactly
            listOf("a" to "2027-09-25", "b" to "2027-09-18", "c" to "2027-09-25")
        outcome.search!!.let {
            it.startDate shouldBe LocalDate.parse("2027-09-18")
            it.endDate shouldBe LocalDate.parse("2027-10-23")
            it.resultCount shouldBe 3
        }
    }

    @Test
    fun `a window that does not start on a Saturday begins with the next Saturday, and at most six weeks are searched`() {
        val outcome = window("2027-09-15", "2027-12-31")
        startDates shouldContainExactly listOf("2027-09-18", "2027-09-25", "2027-10-02", "2027-10-09", "2027-10-16", "2027-10-23")
        mapper.readTree(outcome.resultForModel).path("weeksNote").asText() shouldContain "first 6 weeks"
    }

    @Test
    fun `a window too short for one Saturday week is refused without a search`() {
        val outcome = window("2027-09-19", "2027-09-24")
        startDates shouldBe emptyList()
        mapper.readTree(outcome.resultForModel).path("error").asText() shouldContain "no Saturday-to-Saturday week"
    }
}
