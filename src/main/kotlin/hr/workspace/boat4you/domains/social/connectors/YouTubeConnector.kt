package hr.workspace.boat4you.domains.social.connectors

import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.bearer
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.query
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * YouTube Data API v3: our one channel for every site (account without a site). Google OAuth with PKCE and offline
 * access (refresh token); resumable upload of one video with title, description, category Travel & Events (19).
 * A vertical video up to 3 minutes becomes a Short by itself. Until the Google project passes YouTube's API audit,
 * YouTube keeps uploads private; the consent screen must be "In production" or refresh tokens die after 7 days.
 */
@Component
class YouTubeConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()

    override val platforms = setOf(SocialPlatform.YOUTUBE)

    override val usesPkce = true

    override fun notConfiguredReason(): String? =
        if (props.googleClientId.isBlank() || props.googleClientSecret.isBlank()) "Google app not configured (SOCIAL_GOOGLE_CLIENT_ID / SECRET)" else null

    private val redirectUri get() = props.callbackUrl("youtube")

    override fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String =
        query(
            "https://accounts.google.com/o/oauth2/v2/auth",
            mapOf(
                "client_id" to props.googleClientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly",
                "access_type" to "offline",
                "prompt" to "consent",
                "include_granted_scopes" to "true",
                "state" to state,
                "code_challenge" to codeChallenge,
                "code_challenge_method" to codeChallenge?.let { "S256" },
            ),
        )

    override fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> {
        val t =
            http.postForm(
                TOKEN_URL,
                mapOf(
                    "code" to code,
                    "client_id" to props.googleClientId,
                    "client_secret" to props.googleClientSecret,
                    "redirect_uri" to redirectUri,
                    "grant_type" to "authorization_code",
                    "code_verifier" to codeVerifier,
                ),
            )
        val tokens =
            Tokens(
                t.path("access_token").asText(),
                t.path("refresh_token").asText("").ifBlank { null },
                Instant.now().plusSeconds(t.path("expires_in").asLong(HOUR_S)),
            )
        val channels = http.get("$API/channels?part=snippet&mine=true", bearer(tokens.accessToken)).path("items")
        if (channels.isEmpty) throw PublishException("This Google account has no YouTube channel")
        return channels.map { ch ->
            val s = ch.path("snippet")
            ConnectedAccount(
                platform = SocialPlatform.YOUTUBE,
                externalId = ch.path("id").asText(),
                name = s.path("title").asText("YouTube"),
                handle = s.path("customUrl").asText("").ifBlank { null }?.let { "youtube.com/$it" },
                avatarUrl = s.path("thumbnails").path("default").path("url").asText("").ifBlank { null },
                tokens = tokens,
            )
        }
    }

    override fun refresh(tokens: Tokens): Tokens? {
        val expires = tokens.expiresAt
        if (expires == null || expires.isAfter(Instant.now().plusSeconds(REFRESH_MARGIN_S))) return null
        val refresh = tokens.refreshToken ?: throw PublishException("YouTube access expired; connect again", tokenInvalid = true)
        val t =
            http.postForm(
                TOKEN_URL,
                mapOf(
                    "client_id" to props.googleClientId,
                    "client_secret" to props.googleClientSecret,
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token",
                ),
            )
        val access = t.path("access_token").asText("").ifBlank { throw PublishException("YouTube refresh refused; connect again", tokenInvalid = true) }
        return Tokens(access, refresh, Instant.now().plusSeconds(t.path("expires_in").asLong(HOUR_S)))
    }

    override fun publish(job: PublishJob): PublishResult {
        val video = job.media.firstOrNull { it.kind == SocialMediaKind.VIDEO } ?: throw PublishException("YouTube needs a video")
        val title = job.title?.takeIf { it.isNotBlank() } ?: throw PublishException("YouTube needs a title")
        val start =
            http.postJson(
                "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status",
                mapOf(
                    "snippet" to mapOf("title" to title.take(MAX_TITLE), "description" to job.text.take(MAX_DESCRIPTION), "categoryId" to TRAVEL),
                    "status" to mapOf("privacyStatus" to if (job.privateOnly) "private" else "public", "selfDeclaredMadeForKids" to false),
                ),
                bearer(job.tokens.accessToken) +
                    mapOf("X-Upload-Content-Type" to video.mimeType, "X-Upload-Content-Length" to video.sizeBytes.toString()),
            )
        val location = start.header("Location") ?: throw PublishException("YouTube gave no upload address", retryable = true)
        val done = http.putFile(location, video.file, bearer(job.tokens.accessToken) + mapOf("Content-Type" to video.mimeType))
        val id = done.body.path("id").asText()
        return PublishResult(id, "https://www.youtube.com/watch?v=$id")
    }

    private companion object {
        const val API = "https://www.googleapis.com/youtube/v3"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val HOUR_S = 3600L
        const val REFRESH_MARGIN_S = 300L
        const val MAX_TITLE = 100
        const val MAX_DESCRIPTION = 5000
        const val TRAVEL = "19"
    }
}
