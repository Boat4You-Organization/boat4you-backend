package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dates in the past are never searched (Mario 3.11.2026, John on 2.11.: "middle to end of June" became June 2026 and the
 * visitor was shown a boat for a week that had already gone, and "no availability" for July to September).
 */
class AiChatToolPastDatesTest {
    private val mapper = ObjectMapper()
    private val startDates = CopyOnWriteArrayList<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val query = ex.requestURI.query.orEmpty().split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
                if (!ex.requestURI.path.startsWith("/public/catalogue") && !ex.requestURI.path.startsWith("/public/locations")) {
                    startDates += query["startDate"].orEmpty()
                }
                val body = (if (ex.requestURI.path.startsWith("/public/catalogue")) "[]" else """{"content":[],"page":{"totalElements":0}}""").toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val tools =
        AiChatToolExecutor("http://127.0.0.1:${server.address.port}", mapper).also {
            it.clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC)
        }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `dates in the past are refused before any search, and the model is told the next year`() {
        val out = tools.searchYachts(mapper.readTree("""{"countryCode":"HR","startDate":"2026-06-13","endDate":"2026-06-20"}"""))
        out.resultForModel shouldContain "in the past"
        out.resultForModel shouldContain "2027"
        startDates shouldBe emptyList()
    }

    @Test
    fun `a window that started in the past searches only the weeks still ahead`() {
        tools.searchYachts(mapper.readTree("""{"countryCode":"HR","windowStart":"2026-10-24","windowEnd":"2026-11-21"}"""))
        startDates shouldContainExactly listOf("2026-11-07", "2026-11-14")
    }

    @Test
    fun `a window all in the past is refused like past dates`() {
        val out = tools.searchYachts(mapper.readTree("""{"countryCode":"HR","windowStart":"2026-06-06","windowEnd":"2026-06-27"}"""))
        out.resultForModel shouldContain "in the past"
        startDates shouldBe emptyList()
    }
}
