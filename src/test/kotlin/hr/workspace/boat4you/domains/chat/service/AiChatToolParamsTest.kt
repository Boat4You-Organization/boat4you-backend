package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Review B8: search_yachts parameters come from the model, so only whitelisted shapes reach our own /public API and
 * every value is encoded - "CATAMARAN&countryCodes=GR" must not add a parameter. A local HTTP server records the URIs.
 */
class AiChatToolParamsTest {
    private val mapper = ObjectMapper()
    private val seen = mutableListOf<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += ex.requestURI.rawPath + "?" + (ex.requestURI.rawQuery ?: "")
                val body =
                    when {
                        ex.requestURI.path.startsWith("/public/locations") -> """{"content":[]}"""
                        ex.requestURI.path.startsWith("/public/catalogue") -> "[]"
                        else -> """{"content":[],"page":{"totalElements":0}}"""
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

    private fun search(input: String) = tools.searchYachts(mapper.readTree(input))

    private fun yachtsQuery(): Map<String, List<String>> =
        seen
            .last { it.startsWith("/public/yachts") }
            .substringAfter('?')
            .split('&')
            .map { it.split('=', limit = 2) }
            .groupBy({ URLDecoder.decode(it[0], StandardCharsets.UTF_8) }, { URLDecoder.decode(it.getOrElse(1) { "" }, StandardCharsets.UTF_8) })

    @Test
    fun `a polluted vessel type is dropped, a real one passes`() {
        search("""{"countryCode":"hr","startDate":"2027-07-03","endDate":"2027-07-10","vesselType":"CATAMARAN&countryCodes=GR"}""")
        val q = yachtsQuery()
        q["countryCodes"] shouldBe listOf("HR")
        q.containsKey("vesselType") shouldBe false

        search("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10","vesselType":"catamaran"}""")
        yachtsQuery()["vesselType"] shouldBe listOf("CATAMARAN")
    }

    @Test
    fun `a place name is encoded on its way to the locations lookup, a polluted one never sent`() {
        search("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10","location":"Kaštela"}""")
        val lookup = seen.single { it.startsWith("/public/locations") }
        lookup shouldContain "name=Ka%C5%A1tela"

        seen.clear()
        search("""{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10","location":"Split&did=1"}""")
        seen.filter { it.startsWith("/public/locations") } shouldHaveSize 0
        seen.last() shouldNotContain "did="
    }

    @Test
    fun `a country code that is no ISO-2 code is refused before any call`() {
        mapper.readTree(search("""{"countryCode":"H1","startDate":"2027-07-03","endDate":"2027-07-10"}""").resultForModel)
            .path("error").asText() shouldContain "countryCode"
        seen shouldHaveSize 0
    }
}
