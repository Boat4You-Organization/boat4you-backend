package hr.workspace.boat4you.domains.social.ai

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.social.connectors.SocialHttp
import hr.workspace.boat4you.domains.social.service.SocialConfigException
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component

/** Where a Higgsfield request stands. */
data class GenerationState(
    val done: Boolean,
    val failed: Boolean,
    val videoUrl: String?,
    val error: String?,
)

/**
 * Higgsfield (image → short video) over its HTTP API: `Authorization: Key {id}:{secret}`, one POST per request to a
 * model endpoint (`social.higgsfield.image-to-video-path`, e.g. `/wan/v2.7/image-to-video`) with `image_url` and a
 * `prompt`; the work is asynchronous, so we keep the request id and poll its status until the result has a video URL.
 *
 * The exact model path, status path and field names must be checked against docs.higgsfield.ai when the account is
 * set up (they are configuration, not code); the parsing below accepts the common shapes (request_id / id,
 * status_url, status completed / succeeded, video.url / output.video.url / video_url).
 */
@Component
class HiggsfieldClient(
    private val props: SocialProperties,
) {
    internal var http: SocialHttp = SocialHttp()

    private fun auth() = mapOf("Authorization" to "Key ${props.higgsfieldKeyId}:${props.higgsfieldKeySecret}", "Accept" to "application/json")

    /** Starts image → video; returns the request id (or the status URL when that is all the API gives). */
    fun submitImageToVideo(
        imageUrl: String,
        prompt: String,
    ): String {
        if (!props.higgsfieldConfigured) throw SocialConfigException("Higgsfield is not configured (SOCIAL_HIGGSFIELD_API_KEY_ID / SECRET)")
        val res =
            http
                .postJson(
                    props.higgsfieldBaseUrl.trimEnd('/') + props.higgsfieldImageToVideoPath,
                    mapOf("image_url" to imageUrl, "prompt" to prompt, "aspect_ratio" to "9:16", "duration" to DURATION_S),
                    auth(),
                ).body
        return listOf(res.path("request_id"), res.path("id"), res.path("status_url"))
            .firstOrNull { it.isTextual && it.asText().isNotBlank() }
            ?.asText()
            ?: error("Higgsfield gave no request id")
    }

    fun status(requestIdOrUrl: String): GenerationState {
        val url =
            if (requestIdOrUrl.startsWith("https://")) {
                requestIdOrUrl
            } else {
                props.higgsfieldBaseUrl.trimEnd('/') + props.higgsfieldStatusPath.replace("{id}", requestIdOrUrl)
            }
        return parse(http.get(url, auth()))
    }

    companion object {
        private const val DURATION_S = 5

        fun parse(j: JsonNode): GenerationState {
            val status = j.path("status").asText("").lowercase()
            val video =
                listOf(
                    j.path("video").path("url"),
                    j.path("output").path("video").path("url"),
                    j.path("result").path("video").path("url"),
                    j.path("video_url"),
                    j.path("output").path("url"),
                ).firstOrNull { it.isTextual && it.asText().startsWith("https://") }?.asText()
            return when {
                status in setOf("failed", "error", "nsfw", "cancelled", "canceled") ->
                    GenerationState(done = true, failed = true, videoUrl = null, error = j.path("error").asText(status))
                video != null && status in setOf("", "completed", "complete", "succeeded", "success", "done") ->
                    GenerationState(done = true, failed = false, videoUrl = video, error = null)
                status in setOf("completed", "complete", "succeeded", "success", "done") ->
                    GenerationState(done = true, failed = true, videoUrl = null, error = "Finished without a video")
                else -> GenerationState(done = false, failed = false, videoUrl = null, error = null)
            }
        }
    }
}
