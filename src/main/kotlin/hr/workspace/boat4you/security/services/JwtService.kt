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

    private val signingKey: SecretKey by lazy {
        val keyBytes: ByteArray = Decoders.BASE64.decode(secretKey)
        Keys.hmacShaKeyFor(keyBytes)
    }

    private val jwtParser: JwtParser by lazy {
        Jwts
            .parser()
            .verifyWith(signingKey)
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
}
