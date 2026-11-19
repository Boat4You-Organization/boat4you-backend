package hr.workspace.boat4you.domains.social.connectors

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.bearer
import hr.workspace.boat4you.domains.social.connectors.SocialHttp.Companion.query
import hr.workspace.boat4you.domains.social.jpa.SocialMediaKind
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.service.SocialProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64

/**
 * Pinterest API v5. One login returns the account's boards and every board becomes its own channel (a pin always goes
 * to one board, so the site of a channel is the site of its board). Access tokens last 30 days and are refreshed with
 * the refresh token. A pin is one photo with a title, a description and the link it leads to (the boat's page): that
 * link is the point of Pinterest for us, Google indexes pins. Video pins are not sent yet.
 *
 * A new Pinterest app has trial access: its pins are visible only to us until Pinterest approves standard access.
 */
@Component
class PinterestConnector(
    private val props: SocialProperties,
) : SocialConnector {
    internal var http: SocialHttp = SocialHttp()

    override val platforms = setOf(SocialPlatform.PINTEREST)

    override fun notConfiguredReason(): String? =
        if (props.pinterestAppId.isBlank() || props.pinterestAppSecret.isBlank()) "Pinterest app not configured (SOCIAL_PINTEREST_APP_ID / SECRET)" else null

    private val redirectUri get() = props.callbackUrl("pinterest")

    private fun basic() = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("${props.pinterestAppId}:${props.pinterestAppSecret}".toByteArray()))

    override fun authorizeUrl(
        state: String,
        codeChallenge: String?,
    ): String =
        query(
            "https://www.pinterest.com/oauth/",
            mapOf("client_id" to props.pinterestAppId, "redirect_uri" to redirectUri, "response_type" to "code", "scope" to SCOPES, "state" to state),
        )

    override fun exchange(
        code: String,
        codeVerifier: String?,
    ): List<ConnectedAccount> {
        val tokens = tokensOf(http.postForm("$API/oauth/token", mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri), basic()), null)
        val auth = bearer(tokens.accessToken)
        val me = http.get("$API/user_account", auth)
        val username = me.path("username").asText("")
        val avatar = me.path("profile_image").asText("").ifBlank { null }
        val boards = http.get(query("$API/boards", mapOf("page_size" to "100")), auth).path("items")
        if (boards.isEmpty) throw PublishException("This Pinterest account has no boards; create one board per site first")
        return boards.map { board(it, username, avatar, tokens) }
    }

    private fun board(
        b: JsonNode,
        username: String,
        avatar: String?,
        tokens: Tokens,
    ): ConnectedAccount {
        val id = b.path("id").asText()
        val name = b.path("name").asText(id)
        return ConnectedAccount(
            platform = SocialPlatform.PINTEREST,
            externalId = id,
            name = name,
            handle = if (username.isBlank()) null else "pinterest.com/$username",
            avatarUrl = avatar,
            tokens = tokens,
            meta = mapOf("boardId" to id, "username" to username),
        )
    }

    override fun refresh(tokens: Tokens): Tokens? {
        val expires = tokens.expiresAt
        val refresh = tokens.refreshToken
        if (refresh == null || expires == null || expires.isAfter(Instant.now().plusSeconds(REFRESH_BEFORE_S))) return null
        return tokensOf(http.postForm("$API/oauth/token", mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to SCOPES), basic()), refresh)
    }

    private fun tokensOf(
        t: JsonNode,
        previousRefresh: String?,
    ): Tokens {
        val access = t.path("access_token").asText("").ifBlank { throw PublishException("Pinterest gave no access token", tokenInvalid = true) }
        return Tokens(access, t.path("refresh_token").asText("").ifBlank { previousRefresh }, Instant.now().plusSeconds(t.path("expires_in").asLong(THIRTY_DAYS_S)))
    }

    override fun publish(job: PublishJob): PublishResult {
        val photo = job.media.firstOrNull { it.kind == SocialMediaKind.IMAGE } ?: throw PublishException("Pinterest needs a photo")
        val body =
            buildMap<String, Any> {
                put("board_id", job.meta["boardId"] ?: job.externalId)
                job.title?.takeIf { it.isNotBlank() }?.let { put("title", it.take(TITLE_MAX)) }
                if (job.text.isNotBlank()) put("description", job.text.take(DESCRIPTION_MAX))
                job.link?.takeIf { it.isNotBlank() }?.let { put("link", it) }
                put("media_source", mapOf("source_type" to "image_url", "url" to photo.url))
            }
        val id = pointOfNoReturn(PINTEREST) { http.postJson("$API/pins", body, bearer(job.tokens.accessToken)).body.path("id").asText().sentOr(PINTEREST) }
        return PublishResult(id, "https://www.pinterest.com/pin/$id/")
    }

    private companion object {
        const val API = "https://api.pinterest.com/v5"
        const val SCOPES = "boards:read,pins:read,pins:write,user_accounts:read"
        const val THIRTY_DAYS_S = 30L * 86_400
        const val REFRESH_BEFORE_S = 5L * 86_400
        const val TITLE_MAX = 100
        const val DESCRIPTION_MAX = 800
        const val PINTEREST = "Pinterest"
    }
}
