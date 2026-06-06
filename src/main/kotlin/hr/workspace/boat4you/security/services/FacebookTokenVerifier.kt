package hr.workspace.boat4you.security.services

import com.fasterxml.jackson.databind.JsonNode
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.exceptions.OAuthEmailMissingException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant

/**
 * The verified identity carried by a Facebook access token. Top-level (not
 * nested) so callers in the same package reference it without qualification —
 * mirrors [GoogleIdentity].
 */
data class FacebookIdentity(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val givenName: String?,
    val familyName: String?,
    val name: String?,
)

/**
 * Verifies a Facebook Login (JS SDK) access token entirely server-side. Unlike
 * Google, Facebook Login for Web returns an opaque ACCESS TOKEN, not a signed
 * JWT — so there is nothing to verify cryptographically in-process. The token
 * is validated by calling Facebook's Graph API with OUR app credentials and is
 * never trusted on the browser's word:
 *
 *  1. GET /debug_token — inspect the token with an app access token
 *     ("appId|appSecret"). We REQUIRE:
 *       - data.is_valid == true,
 *       - data.app_id == OUR configured app id (THE core security check — this
 *         is the equivalent of Google's `aud`; rejecting tokens minted for a
 *         different Facebook app prevents an attacker reusing a token issued to
 *         some other app against us),
 *       - data.expires_at == 0 (never-expiring) OR still in the future.
 *  2. GET /me — read id, email, first_name, last_name with the user token.
 *
 * Any validation/transport failure surfaces as a generic [InternalLoginException]
 * (BAD_CREDENTIALS) — same as a wrong password — so the endpoint leaks no
 * parser/account/Graph detail. The ONE deliberate non-generic outcome is a
 * missing email: Facebook only returns `email` when it is the account's
 * confirmed email, so a present email means verified, and an absent email
 * throws [OAuthEmailMissingException] (a clear actionable 4xx) rather than
 * silently creating an emailless account.
 */
@Service
class FacebookTokenVerifier(
    @Value("\${application.oauth.facebook.app-id:}")
    private val facebookAppId: String,
    @Value("\${application.oauth.facebook.app-secret:}")
    private val facebookAppSecret: String,
) {
    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(GRAPH_BASE_URL)
            .build()

    fun verify(accessTokenRaw: String): FacebookIdentity {
        if (facebookAppId.isBlank() || facebookAppSecret.isBlank()) {
            // Misconfiguration, not a user error — surfaces as 500. Never reached
            // in a correctly-provisioned env (FACEBOOK_OAUTH_APP_ID / _APP_SECRET set).
            throw IllegalStateException("Facebook login is not configured")
        }
        val accessToken = accessTokenRaw.trim()
        if (accessToken.isEmpty()) {
            throw badCredentials()
        }

        val userId = debugTokenAndExtractUserId(accessToken)
        val me = fetchMe(accessToken)

        val email = me.path("email").asText("").trim()
        if (email.isBlank()) {
            // Distinct, deliberate outcome — NOT an auth failure to hide. Our
            // account model is email-keyed; refuse rather than create an
            // emailless account, and tell the user to sign up with email.
            throw OAuthEmailMissingException(PROVIDER_FACEBOOK)
        }

        return FacebookIdentity(
            subject = userId,
            email = email.lowercase(),
            // Facebook only returns `email` when it is the account's confirmed
            // email, so presence == verified.
            emailVerified = true,
            givenName = me.path("first_name").asText(null)?.takeIf { it.isNotBlank() },
            familyName = me.path("last_name").asText(null)?.takeIf { it.isNotBlank() },
            name = me.path("name").asText(null)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Calls /debug_token with the app access token ("appId|appSecret"), enforces
     * the ownership + validity rules, and returns the verified Facebook user id.
     */
    private fun debugTokenAndExtractUserId(userAccessToken: String): String {
        val appAccessToken = "$facebookAppId|$facebookAppSecret"
        val root = graphGet("/debug_token") {
            it.queryParam("input_token", userAccessToken)
                .queryParam("access_token", appAccessToken)
        }
        // Graph returns { "data": { ... } } on success, { "error": { ... } } on failure.
        val data = root.path("data")
        if (data.isMissingNode || !data.isObject) {
            throw badCredentials()
        }
        if (!data.path("is_valid").asBoolean(false)) {
            throw badCredentials()
        }
        // CORE CHECK: the token must have been minted for OUR app. Rejecting a
        // token issued to a different Facebook app is the equivalent of Google's
        // `aud` check — without it, any valid FB token would authenticate here.
        val appId = data.path("app_id").asText("")
        if (appId != facebookAppId) {
            throw badCredentials()
        }
        // expires_at == 0 means a long-lived / never-expiring token; otherwise it
        // is a unix-seconds expiry that must still be in the future.
        val expiresAt = data.path("expires_at").asLong(-1L)
        if (expiresAt < 0L) {
            throw badCredentials()
        }
        if (expiresAt != 0L && Instant.ofEpochSecond(expiresAt).isBefore(Instant.now())) {
            throw badCredentials()
        }
        val userId = data.path("user_id").asText("").trim()
        if (userId.isBlank()) {
            throw badCredentials()
        }
        return userId
    }

    private fun fetchMe(userAccessToken: String): JsonNode {
        val root = graphGet("/me") {
            it.queryParam("fields", "id,email,first_name,last_name")
                .queryParam("access_token", userAccessToken)
        }
        if (root.has("error") || !root.path("id").asText("").let { it.isNotBlank() }) {
            throw badCredentials()
        }
        return root
    }

    /**
     * Single point of Graph I/O + defensive JSON parse. Any transport error or
     * non-JSON / unparseable body becomes a generic auth failure — Graph can
     * return an { "error": {...} } object with 4xx that the caller then inspects.
     */
    private fun graphGet(
        path: String,
        uriCustomizer: (org.springframework.web.util.UriBuilder) -> org.springframework.web.util.UriBuilder,
    ): JsonNode =
        try {
            restClient
                .get()
                .uri { uriCustomizer(it.path(path)).build() }
                .retrieve()
                // Don't let Graph's 4xx (invalid token) throw its own exception —
                // we want to parse the error body uniformly, then map to generic.
                .onStatus({ true }, { _, _ -> })
                .body(JsonNode::class.java)
                ?: throw badCredentials()
        } catch (_: Exception) {
            throw badCredentials()
        }

    private fun badCredentials() =
        InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, OAUTH_EMAIL_PLACEHOLDER)

    companion object {
        private const val GRAPH_BASE_URL = "https://graph.facebook.com"
        private const val PROVIDER_FACEBOOK = "FACEBOOK"
        private const val OAUTH_EMAIL_PLACEHOLDER = "oauth:facebook"
    }
}
