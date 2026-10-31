package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Minimal Anthropic Messages API client for the site chat assistant. No SDK
 * dependency on purpose — one endpoint, plain Jackson trees, and the caller
 * (AiChatService) owns the tool-use loop. The API key lives ONLY in the
 * server env (`ANTHROPIC_API_KEY`); when it is blank the whole chat feature
 * reports itself disabled and the web widget stays hidden.
 */
@Component
class AnthropicClient(
    @Value("\${chat.anthropic-api-key:}") private val apiKey: String,
    @Value("\${chat.anthropic-base-url:https://api.anthropic.com}") baseUrl: String,
    @Value("\${chat.model:claude-haiku-4-5-20251001}") val model: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                // 25 s (was 60): a slow answer must not hold the request thread (review B5).
                setReadTimeout(READ_TIMEOUT)
            },
        )
        .build()

    /**
     * At most [MAX_CONCURRENT_CALLS] Anthropic calls at once on this node (review B5): a few IPs firing messages in
     * parallel must not tie up every request thread of cusma2, the only API node.
     */
    private val inFlight = Semaphore(MAX_CONCURRENT_CALLS)

    fun isEnabled(): Boolean = apiKey.isNotBlank()

    /**
     * The system prompt in two parts (prompt caching, BACKEND-18): [cached] is the stable text that follows the tools in
     * the request prefix and is sent with `cache_control`; [tail] is the per-request text after the breakpoint (page
     * context, visitor name, date) and is never cached.
     */
    data class SystemPrompt(val cached: String, val tail: String) {
        /** Both parts as one text, as the model reads them. */
        val full: String get() = if (tail.isBlank()) cached else "$cached\n\n$tail"
    }

    /**
     * One /v1/messages call. `messages` and `tools` are prebuilt Jackson
     * nodes; returns the raw response tree (stop_reason + content blocks) or
     * throws on transport/API errors — the service maps that to a polite
     * apology, never a 500 to the visitor.
     */
    fun createMessage(system: SystemPrompt, messages: JsonNode, tools: JsonNode?, maxTokens: Int = 700, toolsOff: Boolean = false): JsonNode {
        if (!inFlight.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS)) throw IllegalStateException("Anthropic call limit reached")
        try {
            return call(system, messages, tools, maxTokens, toolsOff)
        } finally {
            inFlight.release()
        }
    }

    private fun call(system: SystemPrompt, messages: JsonNode, tools: JsonNode?, maxTokens: Int, toolsOff: Boolean): JsonNode {
        val body = objectMapper.createObjectNode().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            putArray("system").apply {
                addObject().apply {
                    put("type", "text")
                    put("text", system.cached)
                    putObject("cache_control").put("type", "ephemeral")
                }
                if (system.tail.isNotBlank()) {
                    addObject().apply {
                        put("type", "text")
                        put("text", system.tail)
                    }
                }
            }
            set<JsonNode>("messages", messages)
            if (tools != null && !tools.isEmpty) {
                // The same tools stay in the request (a stable cached prefix, and the history holds tool calls); with
                // toolsOff the model must answer in text.
                set<JsonNode>("tools", tools)
                if (toolsOff) putObject("tool_choice").put("type", "none")
            }
        }

        val response = client.post()
            .uri("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(String::class.java)

        val tree = objectMapper.readTree(response)
        if (tree.has("error")) {
            log.warn("Anthropic API error: ${tree.path("error").path("message").asText()}")
            throw IllegalStateException("Anthropic API error")
        }
        if (log.isDebugEnabled) {
            // Token counts only, never prompt text. input_tokens is the uncached remainder: with both cache counters at 0
            // it is the whole prompt (tools, system, messages), which shows how far under the model's minimum cacheable
            // prefix the request is; with either counter above 0 that counter is the cached prefix size.
            val usage = tree.path("usage")
            log.debug(
                "Anthropic usage: input_tokens={} cache_creation_input_tokens={} cache_read_input_tokens={}",
                usage.path("input_tokens").asInt(),
                usage.path("cache_creation_input_tokens").asInt(),
                usage.path("cache_read_input_tokens").asInt(),
            )
        }
        return tree
    }

    internal companion object {
        const val MAX_CONCURRENT_CALLS = 6
        const val PERMIT_WAIT_SECONDS = 2L
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(25)
    }
}
