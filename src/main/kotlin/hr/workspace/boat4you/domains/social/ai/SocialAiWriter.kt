package hr.workspace.boat4you.domains.social.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.social.service.SocialConfigException
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/** What the AI writes for one day and one site: a text per network and the prompt for the Higgsfield video. */
data class AiPostDraft(
    val topic: String,
    val mainText: String,
    val shortText: String,
    val instagramCaption: String,
    val tiktokCaption: String,
    val youtubeTitle: String,
    val youtubeDescription: String,
    val videoPrompt: String,
)

/**
 * Writes the daily post with Claude through the Messages API, the same plain-HTTP way and the same key
 * (`ANTHROPIC_API_KEY`) as the site chat's AnthropicClient. Structured output (`output_config.format` JSON schema), so
 * the answer is always the [AiPostDraft] fields. Model `social.ai.model` (default claude-opus-5-5) at effort medium,
 * with server-side refusal fallback on. Facts come only from our catalogue (the boat JSON in the prompt); the prompt
 * forbids prices and invented facts.
 */
@Component
class SocialAiWriter(
    private val props: SocialProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(props.anthropicBaseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(CONNECT_S))
                    setReadTimeout(Duration.ofSeconds(READ_S))
                },
            ).build()
    }

    fun write(
        siteName: String,
        siteUrl: String,
        language: String,
        guidance: String?,
        facts: Map<String, Any?>,
        recentTopics: List<String>,
    ): AiPostDraft {
        if (!props.aiConfigured) throw SocialConfigException("ANTHROPIC_API_KEY is not set")
        val body =
            mapOf(
                "model" to props.aiModel,
                "max_tokens" to MAX_TOKENS,
                "system" to system(siteName, siteUrl, language),
                "messages" to listOf(mapOf("role" to "user", "content" to userMessage(facts, guidance, recentTopics))),
                "output_config" to mapOf("effort" to "medium", "format" to mapOf("type" to "json_schema", "schema" to SCHEMA)),
                "fallbacks" to "default",
            )
        val raw =
            client
                .post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", props.anthropicApiKey)
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", FALLBACK_BETA)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String::class.java) ?: error("Empty answer from the AI")
        val res = objectMapper.readTree(raw)
        if (res.path("stop_reason").asText() == "refusal") error("The AI declined this post")
        if (res.path("stop_reason").asText() == "max_tokens") error("The AI answer was cut off")
        val text =
            res.path("content").firstOrNull { it.path("type").asText() == "text" }?.path("text")?.asText()
                ?: error("The AI gave no text")
        val j: JsonNode = objectMapper.readTree(text)
        log.info("Social AI post written for {} ({} in, {} out tokens)", siteName, res.path("usage").path("input_tokens").asInt(), res.path("usage").path("output_tokens").asInt())
        return AiPostDraft(
            topic = j.path("topic").asText(),
            mainText = j.path("main_text").asText(),
            shortText = j.path("short_text").asText(),
            instagramCaption = j.path("instagram_caption").asText(),
            tiktokCaption = j.path("tiktok_caption").asText(),
            youtubeTitle = j.path("youtube_title").asText().replace("<", "").replace(">", ""),
            youtubeDescription = j.path("youtube_description").asText().replace("<", "").replace(">", ""),
            videoPrompt = j.path("video_prompt").asText(),
        )
    }

    private fun system(
        siteName: String,
        siteUrl: String,
        language: String,
    ) = """
        You write the daily social media post for $siteName ($siteUrl), a yacht and catamaran charter website run by
        Boat4You from Split, Croatia. Write in ${languageName(language)}.

        Voice: warm, confident, concrete, like a skipper who knows these waters. Short sentences. No clichés such as
        "unforgettable", "paradise", "dream vacation" or "look no further". At most two emoji in the main text, none in
        titles. Never use ALL CAPS.

        Facts: use only what the boat data in the message says (name, model, year, cabins, berths, length, home marina,
        region). Never state or estimate a price, discount, availability or date, and never invent features, awards or
        reviews. If a fact is missing, leave it out.

        Every text ends with the boat's page link when the network allows clickable links (main_text, short_text,
        youtube_description); Instagram and TikTok captions say "link in bio" instead of a URL.

        Lengths: main_text 400-900 characters (Facebook, LinkedIn); short_text at most 240 characters including the link
        (X, Bluesky, Threads); instagram_caption at most 1500 characters with 8-15 relevant hashtags at the end;
        tiktok_caption at most 300 characters with 3-6 hashtags; youtube_title at most 90 characters;
        youtube_description 300-1000 characters. video_prompt: one English sentence describing a slow, realistic camera
        move over the boat photo (e.g. "slow aerial push-in over the catamaran at anchor, gentle waves, golden hour"),
        no text overlays, no people's faces.
        """.trimIndent()

    private fun userMessage(
        facts: Map<String, Any?>,
        guidance: String?,
        recentTopics: List<String>,
    ): String =
        buildString {
            append("Boat data (JSON):\n")
            append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts))
            if (!guidance.isNullOrBlank()) append("\n\nOur notes for this season (follow them):\n").append(guidance.trim())
            if (recentTopics.isNotEmpty()) append("\n\nRecent posts on this site (do not repeat their angle):\n").append(recentTopics.joinToString("\n") { "- $it" })
            append("\n\nWrite today's post about this boat.")
        }

    private fun languageName(code: String) =
        when (code.lowercase()) {
            "hr" -> "Croatian"
            "de" -> "German"
            "it" -> "Italian"
            "fr" -> "French"
            "es" -> "Spanish"
            "pl" -> "Polish"
            "nl" -> "Dutch"
            else -> "English"
        }

    private companion object {
        const val API_VERSION = "2026-10-21"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val MAX_TOKENS = 16000
        const val CONNECT_S = 10L
        const val READ_S = 300L

        val SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to
                    listOf("topic", "main_text", "short_text", "instagram_caption", "tiktok_caption", "youtube_title", "youtube_description", "video_prompt"),
                "properties" to
                    mapOf(
                        "topic" to mapOf("type" to "string", "description" to "the angle of this post in a few words"),
                        "main_text" to mapOf("type" to "string"),
                        "short_text" to mapOf("type" to "string"),
                        "instagram_caption" to mapOf("type" to "string"),
                        "tiktok_caption" to mapOf("type" to "string"),
                        "youtube_title" to mapOf("type" to "string"),
                        "youtube_description" to mapOf("type" to "string"),
                        "video_prompt" to mapOf("type" to "string"),
                    ),
            )
    }
}
