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
 * Charters run Saturday to Saturday only, in whole weeks (Mario 12.11.2026, Allison on Europe Yachts asked "from the 9th
 * or 11th" of May and IRIS answered "Sure, let me check" and searched from Sunday 9 May). Other dates are never searched or
 * checked: the model is told the rule and the nearest Saturday weeks to offer.
 */
class AiChatToolSaturdayWeekTest {
    private val mapper = ObjectMapper()
    private val calls = CopyOnWriteArrayList<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                calls += ex.requestURI.toString()
                val body = (if (ex.requestURI.path.startsWith("/public/catalogue")) "[]" else """{"content":[],"page":{"totalElements":0}}""").toByteArray()
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

    private fun search(from: String, to: String) =
        mapper.readTree(tools.searchYachts(mapper.readTree("""{"countryCode":"HR","location":"Trogir","startDate":"$from","endDate":"$to"}""")).resultForModel)

    private fun check(from: String, to: String) =
        mapper.readTree(tools.checkBoat(mapper.readTree("""{"boat":"bavaria-34-bimbo","startDate":"$from","endDate":"$to"}"""), null).resultForModel)

    @Test
    fun `Allison's 9 to 11 May is refused before any search, with the Saturday weeks around it`() {
        val out = search("2027-05-09", "2027-05-11")
        out.path("error").asText() shouldContain "Saturday to Saturday"
        out.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15", "2027-05-15 to 2027-05-22")
        out.path("hint").asText() shouldContain "never say you will check"
        calls shouldBe emptyList()
    }

    @Test
    fun `a Sunday-to-Sunday week is refused too and keeps its length in the offered weeks`() {
        val out = search("2027-05-09", "2027-05-23")
        out.path("error").asText() shouldContain "Saturday to Saturday"
        out.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-22", "2027-05-15 to 2027-05-29")
        calls shouldBe emptyList()
    }

    @Test
    fun `a Saturday start with a stay that is not whole weeks is refused`() {
        val out = search("2027-05-08", "2027-05-12")
        out.path("error").asText() shouldContain "Saturday to Saturday"
        out.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15")
        calls shouldBe emptyList()
    }

    @Test
    fun `check_boat refuses other days without looking the boat up`() {
        val out = check("2027-05-11", "2027-05-18")
        out.path("error").asText() shouldContain "Saturday to Saturday"
        out.path("nearestWeeks").map { it.asText() } shouldContainExactly listOf("2027-05-08 to 2027-05-15", "2027-05-15 to 2027-05-22")
        calls shouldBe emptyList()
    }

    @Test
    fun `Saturday weeks of one to four weeks are searched as before`() {
        listOf("2027-05-15", "2027-05-22", "2027-05-29", "2027-06-05").forEach { to ->
            search("2027-05-08", to).toString() shouldNotContain "Saturday to Saturday"
        }
        calls.count { it.startsWith("/public/yachts?") } shouldBe 4
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
