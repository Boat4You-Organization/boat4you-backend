package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

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
                setReadTimeout(Duration.ofSeconds(60))
            },
        )
        .build()

    fun isEnabled(): Boolean = apiKey.isNotBlank()

    /**
     * One /v1/messages call. `messages` and `tools` are prebuilt Jackson
     * nodes; returns the raw response tree (stop_reason + content blocks) or
     * throws on transport/API errors — the service maps that to a polite
     * apology, never a 500 to the visitor.
     */
    fun createMessage(system: String, messages: JsonNode, tools: JsonNode?, maxTokens: Int = 700): JsonNode {
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
}
