package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Charters run Saturday to Saturday, in whole weeks (Mario 12.11.2026, Allison on Europe Yachts asked "from the 9th or 11th"
 * of May and IRIS answered "Sure, let me check" and searched from Sunday 9 May). Other dates are refused with the nearest
 * Saturday weeks - unless a boat really is offered on exactly those days (Mario: rare, "then it may mention it").
 */
class AiChatToolSaturdayWeekTest {
    private val mapper = ObjectMapper()
    private val calls = CopyOnWriteArrayList<String>()

    /** Rows the search answers with (`/public/yachts?...`). */
    @Volatile private var rows = "[]"
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                calls += ex.requestURI.toString()
                val path = ex.requestURI.path
                val body =
                    when {
                        path.startsWith("/public/catalogue") || path.endsWith("/standard-offers") -> "[]"
                        path.startsWith("/public/yachts/") -> """{"id":7,"slug":"bavaria-34-bimbo","name":"Bimbo","modelName":"Bavaria 34","location":{"name":"Trogir"}}"""
                        path == "/public/yachts" -> """{"content":$rows,"page":{"totalElements":1}}"""
                        else -> """{"content":[],"page":{"totalElements":0}}"""
                    }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val tools =
        AiChatToolExecutor("http://127.0.0.1:${server.address.port}", mapper).also {
            it.clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC)
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun row(from: String, to: String) =
        """[{"id":7,"slug":"bavaria-34-bimbo","name":"Bimbo","modelName":"Bavaria 34","clientPriceEur":100,"numberOfDays":7,""" +
            """"offerDateFrom":"$from","offerDateTo":"$to","location":{"name":"Trogir"}}]"""

    private fun searchOutcome(from: String, to: String) =
        tools.searchYachts(mapper.readTree("""{"countryCode":"HR","startDate":"$from","endDate":"$to"}"""))

    private fun search(from: String, to: String) = mapper.readTree(searchOutcome(from, to).resultForModel)

    private fun checkOutcome(from: String, to: String) =
        tools.checkBoat(mapper.readTree("""{"boat":"bavaria-34-bimbo","startDate":"$from","endDate":"$to"}"""), null)

    private val searches get() = calls.count { it.startsWith("/public/yachts?") }

    @Test
    fun `Allison's 9 to 11 May is refused before any search, with the Saturday weeks around it`() {
        val out = searchOutcome("2027-05-09", "2027-05-11")
        val model = mapper.readTree(out.resultForModel)
        model.path("error").asText() shouldContain "Saturday to Saturday"
        model.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15", "2027-05-15 to 2027-05-22")
        model.path("hint").asText() shouldContain "never say you will check"
        out.dropsRoundText shouldBe true
        calls shouldBe emptyList()
    }

    @Test
    fun `a Sunday fortnight no boat is offered for is refused and keeps its length in the offered weeks`() {
        rows = row("2027-05-08", "2027-05-22")
        val out = searchOutcome("2027-05-09", "2027-05-23")
        val model = mapper.readTree(out.resultForModel)
        model.path("error").asText() shouldContain "Saturday to Saturday"
        model.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-22", "2027-05-15 to 2027-05-29")
        out.dropsRoundText shouldBe true
        out.cards shouldBe null
    }

    @Test
    fun `a boat really offered on exactly the asked Sunday week is listed, with a note that it is not the usual week`() {
        rows = row("2027-05-09", "2027-05-16")
        val out = searchOutcome("2027-05-09", "2027-05-16")
        val model = mapper.readTree(out.resultForModel)
        model.has("error") shouldBe false
        model.path("yachts").single().path("dates").asText() shouldBe "exact"
        model.path("offStandardNote").asText() shouldContain "Saturday to Saturday"
        model.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15", "2027-05-15 to 2027-05-22")
        out.dropsRoundText shouldBe false
        model.path("offStandardNote").asText() shouldContain "confirmed by our team"
        model.path("totalAvailable").asInt() shouldBe 1
        out.cards!!.single().path("slug").asText() shouldBe "bavaria-34-bimbo"
    }

    @Test
    fun `a row without its own offer dates never counts as offered on the asked days`() {
        rows = """[{"id":7,"slug":"bavaria-34-bimbo","name":"Bimbo","modelName":"Bavaria 34","clientPriceEur":100,"location":{"name":"Trogir"}}]"""
        search("2027-05-09", "2027-05-16").path("error").asText() shouldContain "Saturday to Saturday"
    }

    @Test
    fun `a Saturday start with a stay that is not whole weeks is refused`() {
        val out = search("2027-05-08", "2027-05-12")
        out.path("error").asText() shouldContain "Saturday to Saturday"
        out.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15")
    }

    @Test
    fun `check_boat refuses other days when the boat is not offered on them`() {
        rows = row("2027-05-08", "2027-05-15")
        val out = checkOutcome("2027-05-11", "2027-05-18")
        val model = mapper.readTree(out.resultForModel)
        model.path("error").asText() shouldContain "Saturday to Saturday"
        model.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15", "2027-05-15 to 2027-05-22")
        out.dropsRoundText shouldBe true
    }

    @Test
    fun `check_boat confirms a boat really offered on exactly the asked days`() {
        rows = row("2027-05-09", "2027-05-16")
        val model = mapper.readTree(checkOutcome("2027-05-09", "2027-05-16").resultForModel)
        model.path("available").asBoolean() shouldBe true
        model.path("startDate").asText() shouldBe "2027-05-09"
        model.path("offStandardNote").asText() shouldContain "Saturday to Saturday"
    }

    @Test
    fun `check_boat refuses a 2-night stay without looking the boat up`() {
        mapper.readTree(checkOutcome("2027-05-09", "2027-05-11").resultForModel).path("error").asText() shouldContain "Saturday to Saturday"
        calls shouldBe emptyList()
    }

    @Test
    fun `Saturday weeks of one to four weeks are searched as before`() {
        listOf("2027-05-15", "2027-05-22", "2027-05-29", "2027-06-05").forEach { to ->
            search("2027-05-08", to).toString() shouldNotContain "Saturday to Saturday"
        }
        searches shouldBe 4
    }

    @Test
    fun `past dates keep their own answer`() {
        search("2026-05-10", "2026-05-12").path("error").asText() shouldContain "in the past"
    }

    @Test
    fun `the next-year hint for past dates is a Saturday week`() {
        // 13.6.2026 is a Saturday, 13.6.2027 a Sunday: the hint is the Saturday week around it, never the Sunday.
        search("2026-06-13", "2026-06-20").path("hint").asText() shouldContain "2027-06-12 to 2027-06-19"
    }
}
