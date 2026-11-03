package hr.workspace.boat4you.domains.social.connectors

import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.query
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Threads API (graph.threads.net): long-lived token (60 days, refreshed before it ends), container → publish, the same
 * pattern as Instagram. Text-only, one photo / video, or a carousel of up to 20.
 */
@Component
class ThreadsConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    override val platforms = setOf(SocialPlatform.THREADS)

    override fun notConfiguredReason(): String? =
        if (props.threadsAppId.isBlank() || props.threadsAppSecret.isBlank()) "Threads app not configured (SOCIAL_THREADS_APP_ID / SECRET)" else null

    private val redirectUri get() = props.callbackUrl("threads")

    override fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String =
        query(
            "https://threads.net/oauth/authorize",
            mapOf(
                "client_id" to props.threadsAppId,
                "redirect_uri" to redirectUri,
                "scope" to "threads_basic,threads_content_publish",
                "response_type" to "code",
                "state" to state,
            ),
        )

    override fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> {
        val short =
            http.postForm(
                "$API/oauth/access_token",
                mapOf(
                    "client_id" to props.threadsAppId,
                    "client_secret" to props.threadsAppSecret,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to redirectUri,
                    "code" to code,
                ),
            )
        val long =
            http.get(
                query(
                    "$API/access_token",
                    mapOf("grant_type" to "th_exchange_token", "client_secret" to props.threadsAppSecret, "access_token" to short.path("access_token").asText()),
                ),
            )
        val token = long.path("access_token").asText()
        val me = http.get(query("$API/v1.0/me", mapOf("fields" to "id,username,threads_profile_picture_url", "access_token" to token)))
        val username = me.path("username").asText("")
        return listOf(
            ConnectedAccount(
                platform = SocialPlatform.THREADS,
                externalId = me.path("id").asText(),
                name = username.ifBlank { "Threads" },
                handle = username.takeIf { it.isNotBlank() }?.let { "@$it" },
                avatarUrl = me.path("threads_profile_picture_url").asText("").ifBlank { null },
                tokens = Tokens(token, null, Instant.now().plusSeconds(long.path("expires_in").asLong(SIXTY_DAYS_S))),
                meta = mapOf("username" to username),
            ),
        )
    }

    override fun refresh(tokens: Tokens): Tokens? {
        val expires = tokens.expiresAt
        if (expires == null || expires.isAfter(Instant.now().plusSeconds(REFRESH_BEFORE_S))) return null
        val t = http.get(query("$API/refresh_access_token", mapOf("grant_type" to "th_refresh_token", "access_token" to tokens.accessToken)))
        return Tokens(t.path("access_token").asText(), null, Instant.now().plusSeconds(t.path("expires_in").asLong(SIXTY_DAYS_S)))
    }

    override fun publish(job: PublishJob): PublishResult {
        val user = job.externalId
        val token = job.tokens.accessToken
        val text = if (job.link.isNullOrBlank() || job.text.contains(job.link)) job.text else "${job.text}\n${job.link}"
        val container =
            when (job.media.size) {
                0 -> create(user, token, mapOf("media_type" to "TEXT", "text" to text))
                1 -> create(user, token, item(job.media[0], carousel = false) + mapOf("text" to text))
                else -> {
                    val children = job.media.map { create(user, token, item(it, carousel = true)) }
                    children.forEach { wait(it, token) }
                    create(user, token, mapOf("media_type" to "CAROUSEL", "children" to children.joinToString(","), "text" to text))
                }
            }
        wait(container, token)
        val id = http.postForm("$API/v1.0/$user/threads_publish", mapOf("creation_id" to container, "access_token" to token)).path("id").asText()
        val permalink = runCatching { http.get(query("$API/v1.0/$id", mapOf("fields" to "permalink", "access_token" to token))).path("permalink").asText("") }
        return PublishResult(id, permalink.getOrNull()?.ifBlank { null })
    }

    private fun item(
        media: PublishMedia,
        carousel: Boolean,
    ): Map<String, String> =
        buildMap {
            if (media.kind == SocialMediaKind.VIDEO) {
                put("media_type", "VIDEO")
                put("video_url", media.url)
            } else {
                put("media_type", "IMAGE")
                put("image_url", media.url)
            }
            if (carousel) put("is_carousel_item", "true")
        }

    private fun create(
        user: String,
        token: String,
        form: Map<String, String>,
    ): String = http.postForm("$API/v1.0/$user/threads", form + mapOf("access_token" to token)).path("id").asText()

    private fun wait(
        container: String,
        token: String,
    ) {
        repeat(POLLS) {
            val s = http.get(query("$API/v1.0/$container", mapOf("fields" to "status,error_message", "access_token" to token)))
            when (s.path("status").asText()) {
                "FINISHED", "PUBLISHED" -> return
                "ERROR", "EXPIRED" -> throw PublishException("Threads could not process the media: ${s.path("error_message").asText("error")}")
                else -> sleeper(POLL_MS)
            }
        }
        throw PublishException("Threads is still processing the media", retryable = true)
    }

    private companion object {
        const val API = "https://graph.threads.net"
        const val SIXTY_DAYS_S = 60L * 86_400
        const val REFRESH_BEFORE_S = 7L * 86_400
        const val POLLS = 120
        const val POLL_MS = 5000L
    }
}
