package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.common.models.ClaimsConstants
import hr.workspace.boat4you.common.models.UserDomainEntity
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import java.util.function.Function
import javax.crypto.SecretKey

@Service
class JwtService {
    @Value("\${application.security.jwt.secret-key}")
    private val secretKey: String? = null

    @Value("\${application.security.jwt.expiration}")
    private val jwtExpiration: Long = 0

    @Value("\${application.security.jwt.refresh-token.expiration}")
    private val refreshExpiration: Long = 0

    // F1-005: bind issued tokens to a known issuer + audience and reject
    // anything that fails to match on parse. A token signed with the
    // same secret for a different service (or an older environment that
    // reused the key) will land here and be rejected with
    // `MissingClaimException` / `IncorrectClaimException`, both
    // subclasses of `JwtException` already caught upstream.
    @Value("\${application.security.jwt.issuer}")
    private val issuer: String? = null

    @Value("\${application.security.jwt.audience}")
    private val audience: String? = null

    private val signingKey: SecretKey by lazy {
        val keyBytes: ByteArray = Decoders.BASE64.decode(secretKey)
        Keys.hmacShaKeyFor(keyBytes)
    }

    private val jwtParser: JwtParser by lazy {
        Jwts
            .parser()
            .verifyWith(signingKey)
            .requireIssuer(issuer)
            .requireAudience(audience)
            .build()
    }

    fun extractAllClaims(token: String): Claims {
        return jwtParser
            .parseSignedClaims(token)
            .payload
    }

    fun extractEmail(token: String): String {
        return extractClaim(token) { obj: Claims -> obj.subject }
    }

    fun <T> extractClaim(
        token: String,
        claimsResolver: Function<Claims, T>,
    ): T {
        val claims: Claims = extractAllClaims(token)
        return claimsResolver.apply(claims)
    }

    fun generateToken(user: UserDomainEntity): Pair<String, Date> {
        val claims =
            mapOf(
                ClaimsConstants.USER_ROLES to user.authorities.map { it.toString() },
                ClaimsConstants.USER_ID to user.userId.toString(),
                ClaimsConstants.USER_EMAIL to user.email,
            )

        return generateToken(claims, user)
    }

    fun generateToken(
        extraClaims: Map<String, Any>,
        user: UserDomainEntity,
    ): Pair<String, Date> {
        val expiryDate = Date(System.currentTimeMillis() + jwtExpiration)
        val token = buildToken(extraClaims, user, expiryDate)
        return Pair(token, expiryDate)
    }

    fun generateRefreshToken(user: UserDomainEntity): Pair<String, Date> {
        val claims =
            mapOf(
                ClaimsConstants.USER_ID to user.userId.toString(),
                ClaimsConstants.USER_EMAIL to user.email,
            )
        val expiryDate = Date(System.currentTimeMillis() + refreshExpiration)
        val token = buildToken(claims, user, expiryDate)
        return Pair(token, expiryDate)
    }

    private fun buildToken(
        extraClaims: Map<String, Any>,
        user: UserDomainEntity,
        expiresAt: Date,
    ): String {
        return Jwts
            .builder()
            .claims()
            .add(extraClaims)
            .subject(user.email)
            .issuer(issuer)
            .audience()
            .add(audience)
            .and()
            .issuedAt(Date())
            .expiration(expiresAt)
            .and()
            .signWith(signingKey)
            .compact()
    }

    fun isTokenValid(
        token: String,
        user: UserDomainEntity,
    ): Boolean {
        val email = extractEmail(token)
        return (email == user.email) && !isTokenExpired(token)
    }

    private fun isTokenExpired(token: String): Boolean {
        return extractExpiration(token).before(Date())
    }

    private fun extractExpiration(token: String): Date {
        return extractClaim(token) { obj: Claims -> obj.expiration }
    }

    /**
     * Short-lived signed token for the verified email-change flow (no DB state — option B).
     * Carries the user id, the email at issue time (so a stale token can't apply after another
     * change), and the requested new email. `type=EMAIL_CHANGE` keeps regular auth tokens from
     * ever being accepted here. Reuses the same signing key + issuer/audience as auth tokens, so
     * the standard parser validates signature/issuer/audience/expiry; we add only the type check.
     */
    fun generateEmailChangeToken(
        userId: Long,
        currentEmail: String,
        newEmail: String,
    ): String {
        val now = Date()
        return Jwts
            .builder()
            .claims()
            .add(
                mapOf(
                    "type" to EMAIL_CHANGE_TYPE,
                    ClaimsConstants.USER_ID to userId.toString(),
                    "currentEmail" to currentEmail,
                    "newEmail" to newEmail,
                ),
            )
            .subject(currentEmail)
            .issuer(issuer)
            .audience()
            .add(audience)
            .and()
            .issuedAt(now)
            .expiration(Date(now.time + EMAIL_CHANGE_TTL_MS))
            .and()
            .signWith(signingKey)
            .compact()
    }

    /**
     * Verifies + decodes an email-change token. Throws [io.jsonwebtoken.JwtException] on bad
     * signature / issuer / audience / expiry (jjwt) or wrong token type.
     */
    fun verifyEmailChangeToken(token: String): EmailChangeClaims {
        val claims = extractAllClaims(token)
        if (claims["type"] != EMAIL_CHANGE_TYPE) {
            throw io.jsonwebtoken.JwtException("Not an email-change token")
        }
        return EmailChangeClaims(
            userId = (claims[ClaimsConstants.USER_ID] as String).toLong(),
            currentEmail = claims["currentEmail"] as String,
            newEmail = claims["newEmail"] as String,
        )
    }

    companion object {
        private const val EMAIL_CHANGE_TYPE = "EMAIL_CHANGE"
        private const val EMAIL_CHANGE_TTL_MS = 60 * 60 * 1000L // 1 hour
    }
}

data class EmailChangeClaims(
    val userId: Long,
    val currentEmail: String,
    val newEmail: String,
)
