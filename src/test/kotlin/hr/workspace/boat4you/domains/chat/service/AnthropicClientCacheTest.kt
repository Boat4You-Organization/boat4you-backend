package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

class AnthropicClientCacheTest {
    private val mapper = ObjectMapper()
    private val bodies = mutableListOf<JsonNode>()
    private val headers = mutableListOf<Map<String, List<String>>>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { ex ->
                bodies.add(mapper.readTree(ex.requestBody.readAllBytes()))
                headers += ex.requestHeaders.mapKeys { it.key.lowercase() }
                val out = """{"stop_reason":"end_turn","content":[{"type":"text","text":"hi"}],"usage":{"cache_read_input_tokens":0}}""".toByteArray()
                ex.sendResponseHeaders(200, out.size.toLong())
                ex.responseBody.use { it.write(out) }
            }
            start()
        }
    private val client = AnthropicClient("test-key", "http://127.0.0.1:${server.address.port}", "claude-sonnet-5", mapper)
    private val tools = mapper.readTree("""[{"name":"search_yachts","description":"d","input_schema":{"type":"object","properties":{}}}]""")
    private val messages = mapper.readTree("""[{"role":"user","content":"hello"}]""")

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `the stable part carries cache_control, the tail does not, tools and limits unchanged`() {
        client.createMessage(AnthropicClient.SystemPrompt("STATIC", "TAIL"), messages, tools)
        val b = bodies.single()
        b.path("system") shouldBe mapper.readTree("""[{"type":"text","text":"STATIC","cache_control":{"type":"ephemeral"}},{"type":"text","text":"TAIL"}]""")
        b.path("tools") shouldBe tools
        b.path("model").asText() shouldBe "claude-sonnet-5"
        b.path("max_tokens").asInt() shouldBe 700
        headers.single()["anthropic-version"] shouldBe listOf("2023-06-01")
        headers.single().containsKey("anthropic-beta") shouldBe false
    }

    @Test
    fun `a blank tail sends one system block`() {
        client.createMessage(AnthropicClient.SystemPrompt("STATIC", ""), messages, tools)
        bodies.single().path("system").size() shouldBe 1
    }

    @Test
    fun `B5 limits are unchanged`() {
        AnthropicClient.MAX_CONCURRENT_CALLS shouldBe 6
        AnthropicClient.PERMIT_WAIT_SECONDS shouldBe 2L
        AnthropicClient.CONNECT_TIMEOUT shouldBe Duration.ofSeconds(10)
        AnthropicClient.READ_TIMEOUT shouldBe Duration.ofSeconds(25)
    }
}
