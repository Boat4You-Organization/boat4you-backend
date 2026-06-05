package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.security.exceptions.InternalLoginException
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Service

/**
 * The verified identity carried by a Google ID token. Top-level (not nested)
 * so callers in the same package reference it without qualification.
 */
data class GoogleIdentity(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val givenName: String?,
    val familyName: String?,
    val name: String?,
)

/**
 * Verifies a Google ID token (the credential Google Identity Services returns
 * in the browser) entirely server-side. We never trust the browser's word for
 * who the user is — only a token that:
 *   - carries a valid RS256 signature from Google's published JWKS,
 *   - has not expired,
 *   - was issued by accounts.google.com (`iss`),
 *   - names OUR web client id as its audience (`aud`),
 * counts. Email + profile claims are then read from the verified token.
 *
 * Signature + expiry are checked by Nimbus against Google's rotating JWKS
 * (fetched + cached on first use). iss / aud / email presence are checked here
 * so the rules are explicit and reviewable in one place. Any failure surfaces
 * as a generic [InternalLoginException] (BAD_CREDENTIALS) — same as a wrong
 * password — so the endpoint leaks no parser/account detail.
 */
@Service
class GoogleTokenVerifier(
    @Value("\${application.oauth.google.client-id:}")
    private val googleClientId: String,
) {
    // Lazily fetches Google's JWKS on first decode (no startup network call).
    private val jwtDecoder: JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build()

    fun verify(idTokenRaw: String): GoogleIdentity {
        if (googleClientId.isBlank()) {
            // Misconfiguration, not a user error — surfaces as 500. Never reached
            // in a correctly-provisioned env (GOOGLE_OAUTH_CLIENT_ID set).
            throw IllegalStateException("Google login is not configured (GOOGLE_OAUTH_CLIENT_ID missing)")
        }
        val idToken = idTokenRaw.trim()
        if (idToken.isEmpty()) {
            throw badCredentials()
        }

        val jwt =
            try {
                jwtDecoder.decode(idToken)
            } catch (_: JwtException) {
                // Bad signature / expired / malformed — failed auth; don't leak detail.
                throw badCredentials()
            }

        val issuer = jwt.issuer?.toString()
        if (issuer != GOOGLE_ISSUER && issuer != GOOGLE_ISSUER_HTTPS) {
            throw badCredentials()
        }
        if (!jwt.audience.contains(googleClientId)) {
            throw badCredentials()
        }

        val subject = jwt.subject
        if (subject.isNullOrBlank()) {
            throw badCredentials()
        }
        val email = jwt.getClaimAsString("email")
        if (email.isNullOrBlank()) {
            throw badCredentials()
        }

        return GoogleIdentity(
            subject = subject,
            email = email.lowercase().trim(),
            emailVerified = readEmailVerified(jwt),
            givenName = jwt.getClaimAsString("given_name"),
            familyName = jwt.getClaimAsString("family_name"),
            name = jwt.getClaimAsString("name"),
        )
    }

    // Google serialises email_verified as a boolean (sometimes the string
    // "true"). Accept either; default false (the caller then refuses to link).
    private fun readEmailVerified(jwt: Jwt): Boolean =
        when (val v = jwt.claims["email_verified"]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }

    private fun badCredentials() = InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, OAUTH_EMAIL_PLACEHOLDER)

    companion object {
        private const val GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs"
        private const val GOOGLE_ISSUER = "accounts.google.com"
        private const val GOOGLE_ISSUER_HTTPS = "https://accounts.google.com"
        private const val OAUTH_EMAIL_PLACEHOLDER = "oauth:google"
    }
}
