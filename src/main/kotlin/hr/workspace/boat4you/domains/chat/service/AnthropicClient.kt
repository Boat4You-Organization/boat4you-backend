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
                setConnectTimeout(Duration.ofSeconds(10))
                // 25 s (was 60): a slow answer must not hold the request thread (review B5).
                setReadTimeout(Duration.ofSeconds(25))
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
     * One /v1/messages call. `messages` and `tools` are prebuilt Jackson
     * nodes; returns the raw response tree (stop_reason + content blocks) or
     * throws on transport/API errors — the service maps that to a polite
     * apology, never a 500 to the visitor.
     */
    fun createMessage(system: String, messages: JsonNode, tools: JsonNode?, maxTokens: Int = 700): JsonNode {
        if (!inFlight.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS)) throw IllegalStateException("Anthropic call limit reached")
        try {
            return call(system, messages, tools, maxTokens)
        } finally {
            inFlight.release()
        }
    }

    private fun call(system: String, messages: JsonNode, tools: JsonNode?, maxTokens: Int): JsonNode {
        val body = objectMapper.createObjectNode().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            set<JsonNode>("messages", messages)
            if (tools != null && !tools.isEmpty) set<JsonNode>("tools", tools)
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
        return tree
    }

    private companion object {
        const val MAX_CONCURRENT_CALLS = 6
        const val PERMIT_WAIT_SECONDS = 2L
    }
}
