package hr.workspace.boat4you.domains.social.connectors

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.bearer
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.query
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import java.io.RandomAccessFile
import java.net.http.HttpRequest
import java.time.Instant

/**
 * TikTok Content Posting API v2 (Login Kit for Web, scopes `user.info.basic,video.publish,video.upload`). Access tokens
 * live 24 h, refresh tokens a year: [refresh] runs before every publish that needs it.
 *
 * Video: `creator_info/query` (allowed privacy levels) → `video/init` with FILE_UPLOAD → PUT the chunks → poll
 * `status/fetch` until PUBLISH_COMPLETE. Photos (1–35): `content/init` with PULL_FROM_URL — TikTok fetches our public
 * media URLs, so their URL prefix must be verified in the TikTok developer portal. Until TikTok audits our app every
 * post is SELF_ONLY (private; `social.tiktok.audited=false`).
 */
@Component
class TikTokConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    override val platforms = setOf(SocialPlatform.TIKTOK)

    override fun notConfiguredReason(): String? =
        if (props.tiktokClientKey.isBlank() || props.tiktokClientSecret.isBlank()) "TikTok app not configured (SOCIAL_TIKTOK_CLIENT_KEY / SECRET)" else null

    private val redirectUri get() = props.callbackUrl("tiktok")

    override fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String =
        query(
            "https://www.tiktok.com/v2/auth/authorize/",
            mapOf(
                "client_key" to props.tiktokClientKey,
                "scope" to "user.info.basic,video.publish,video.upload",
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "state" to state,
            ),
        )

    override fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> {
        val t =
            http.postForm(
                "$API/v2/oauth/token/",
                mapOf(
                    "client_key" to props.tiktokClientKey,
                    "client_secret" to props.tiktokClientSecret,
                    "code" to code,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to redirectUri,
                ),
            )
        val tokens = tokensOf(t) ?: throw PublishException("TikTok gave no token: ${t.path("error_description").asText(t.toString().take(200))}")
        val user = http.get(query("$API/v2/user/info/", mapOf("fields" to "open_id,avatar_url,display_name,username")), bearer(tokens.accessToken))
        val u = user.path("data").path("user")
        val username = u.path("username").asText("")
        return listOf(
            ConnectedAccount(
                platform = SocialPlatform.TIKTOK,
                externalId = u.path("open_id").asText(t.path("open_id").asText()),
                name = u.path("display_name").asText(username.ifBlank { "TikTok" }),
                handle = username.takeIf { it.isNotBlank() }?.let { "@$it" },
                avatarUrl = u.path("avatar_url").asText("").ifBlank { null },
                tokens = tokens,
                meta = mapOf("username" to username),
                privateOnly = !props.tiktokAudited,
            ),
        )
    }

    override fun refresh(tokens: Tokens): Tokens? {
        val expires = tokens.expiresAt
        if (expires == null || expires.isAfter(Instant.now().plusSeconds(REFRESH_MARGIN_S))) return null
        val refresh = tokens.refreshToken ?: throw PublishException("TikTok token expired; connect again", tokenInvalid = true)
        val t =
            http.postForm(
                "$API/v2/oauth/token/",
                mapOf(
                    "client_key" to props.tiktokClientKey,
                    "client_secret" to props.tiktokClientSecret,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                ),
            )
        return tokensOf(t) ?: throw PublishException("TikTok refresh refused; connect again", tokenInvalid = true)
    }

    private fun tokensOf(t: JsonNode): Tokens? {
        val access = t.path("access_token").asText("").ifBlank { return null }
        return Tokens(access, t.path("refresh_token").asText("").ifBlank { null }, Instant.now().plusSeconds(t.path("expires_in").asLong(DAY_S)))
    }

    override fun publish(job: PublishJob): PublishResult {
        val auth = bearer(job.tokens.accessToken)
        val creator = http.postJson("$API/v2/post/publish/creator_info/query/", emptyMap<String, Any>(), auth).body.path("data")
        val options = creator.path("privacy_level_options").map { it.asText() }
        val privacy =
            when {
                !job.privateOnly && "PUBLIC_TO_EVERYONE" in options -> "PUBLIC_TO_EVERYONE"
                "SELF_ONLY" in options -> "SELF_ONLY"
                else -> options.firstOrNull() ?: "SELF_ONLY"
            }
        val video = job.media.firstOrNull { it.kind == SocialMediaKind.VIDEO }
        val publishId =
            if (video != null) uploadVideo(job, video, privacy, auth) else postPhotos(job, privacy, auth)
        val done = waitPublished(publishId, auth)
        val username = job.meta["username"].orEmpty()
        val postId = done.path("publicaly_available_post_id").path(0).asText("").ifBlank { null }
        val url =
            when {
                postId != null && username.isNotBlank() -> "https://www.tiktok.com/@$username/video/$postId"
                username.isNotBlank() -> "https://www.tiktok.com/@$username"
                else -> null
            }
        return PublishResult(postId ?: publishId, url)
    }

    private fun uploadVideo(
        job: PublishJob,
        video: PublishMedia,
        privacy: String,
        auth: Map<String, String>,
    ): String {
        val size = video.sizeBytes
        val chunk = if (size <= MAX_SINGLE_CHUNK) size else CHUNK
        val count = if (size <= MAX_SINGLE_CHUNK) 1 else (size / CHUNK).toInt()
        val init =
            http
                .postJson(
                    "$API/v2/post/publish/video/init/",
                    mapOf(
                        "post_info" to
                            mapOf(
                                "title" to job.text.take(MAX_CAPTION),
                                "privacy_level" to privacy,
                                "disable_duet" to false,
                                "disable_comment" to false,
                                "disable_stitch" to false,
                                "video_cover_timestamp_ms" to COVER_MS,
                            ),
                        "source_info" to mapOf("source" to "FILE_UPLOAD", "video_size" to size, "chunk_size" to chunk, "total_chunk_count" to count),
                    ),
                    auth,
                ).body
                .path("data")
        val uploadUrl = init.path("upload_url").asText()
        RandomAccessFile(video.file.toFile(), "r").use { file ->
            for (i in 0 until count) {
                val first = i * chunk
                // The last chunk carries the remainder (TikTok: total_chunk_count = floor(size / chunk_size)).
                val last = if (i == count - 1) size - 1 else first + chunk - 1
                val bytes = ByteArray((last - first + 1).toInt())
                file.seek(first)
                file.readFully(bytes)
                http.put(
                    uploadUrl,
                    HttpRequest.BodyPublishers.ofByteArray(bytes),
                    mapOf("Content-Type" to video.mimeType, "Content-Range" to "bytes $first-$last/$size"),
                )
            }
        }
        return init.path("publish_id").asText()
    }

    private fun postPhotos(
        job: PublishJob,
        privacy: String,
        auth: Map<String, String>,
    ): String {
        val photos = job.media.filter { it.kind == SocialMediaKind.IMAGE }
        if (photos.isEmpty()) throw PublishException("TikTok needs a video or photos")
        return http
            .postJson(
                "$API/v2/post/publish/content/init/",
                mapOf(
                    "post_info" to
                        mapOf(
                            "title" to (job.title?.takeIf { it.isNotBlank() } ?: job.text.lineSequence().first()).take(MAX_PHOTO_TITLE),
                            "description" to job.text.take(MAX_PHOTO_DESCRIPTION),
                            "privacy_level" to privacy,
                            "disable_comment" to false,
                            "auto_add_music" to true,
                        ),
                    "source_info" to mapOf("source" to "PULL_FROM_URL", "photo_cover_index" to 0, "photo_images" to photos.map { it.url }),
                    "post_mode" to "DIRECT_POST",
                    "media_type" to "PHOTO",
                ),
                auth,
            ).body
            .path("data")
            .path("publish_id")
            .asText()
    }

    private fun waitPublished(
        publishId: String,
        auth: Map<String, String>,
    ): JsonNode {
        repeat(POLLS) {
            val data = http.postJson("$API/v2/post/publish/status/fetch/", mapOf("publish_id" to publishId), auth).body.path("data")
            when (data.path("status").asText()) {
                "PUBLISH_COMPLETE" -> return data
                "FAILED" -> throw PublishException("TikTok refused the post: ${data.path("fail_reason").asText("unknown")}")
                else -> sleeper(POLL_MS)
            }
        }
        throw PublishException("TikTok is still processing the post", retryable = true)
    }

    private companion object {
        const val API = "https://open.tiktokapis.com"
        const val DAY_S = 86_400L
        const val REFRESH_MARGIN_S = 600L
        const val CHUNK = 10L * 1024 * 1024
        const val MAX_SINGLE_CHUNK = 64L * 1024 * 1024
        const val MAX_CAPTION = 2200
        const val MAX_PHOTO_TITLE = 90
        const val MAX_PHOTO_DESCRIPTION = 4000
        const val COVER_MS = 1000
        const val POLLS = 120
        const val POLL_MS = 5000L
    }
}
