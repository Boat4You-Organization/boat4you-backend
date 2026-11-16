package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.common.models.ClaimsConstants
import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.RequestContextFactory
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.jpa.TokenService
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.support.StaticMessageSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.util.Base64

/**
 * Security audit 15.11.2026 (MEDIUM): /auth/refreshToken renewed an access token too (a stolen one never expired), and
 * a refresh token (3 days) was accepted as the Bearer of API calls. Now each is only what it is; tokens issued before
 * the type claim are told apart by the roles an access token carries.
 */
class JwtTokenTypeTest {
    private val jwt =
        JwtService().also {
            ReflectionTestUtils.setField(it, "secretKey", Base64.getEncoder().encodeToString(ByteArray(32) { i -> (i * 7 + 3).toByte() }))
            ReflectionTestUtils.setField(it, "jwtExpiration", 86_400_000L)
            ReflectionTestUtils.setField(it, "refreshExpiration", 259_200_000L)
            ReflectionTestUtils.setField(it, "issuer", "boat4you-ws")
            ReflectionTestUtils.setField(it, "audience", "boat4you-web")
        }
    private val dbUser =
        UserEntity().apply {
            id = 42
            email = "ana@example.com"
        }
    private val user = UserDomainEntity(dbUser)

    private fun claims(token: String) = jwt.extractAllClaims(token)

    @Test
    fun `an access token is only an access token, a refresh token only a refresh token`() {
        val access = claims(jwt.generateToken(user).first)
        val refresh = claims(jwt.generateRefreshToken(user).first)

        jwt.isAccessToken(access) shouldBe true
        jwt.isRefreshToken(access) shouldBe false
        jwt.isAccessToken(refresh) shouldBe false
        jwt.isRefreshToken(refresh) shouldBe true
    }

    @Test
    fun `tokens issued before the type claim keep working as what they were`() {
        val legacyAccess =
            claims(
                jwt
                    .generateToken(
                        mapOf(
                            ClaimsConstants.USER_ROLES to listOf("USER"),
                            ClaimsConstants.USER_ID to "42",
                            ClaimsConstants.USER_EMAIL to user.email,
                        ),
                        user,
                    ).first,
            )
        val legacyRefresh =
            claims(jwt.generateToken(mapOf(ClaimsConstants.USER_ID to "42", ClaimsConstants.USER_EMAIL to user.email), user).first)

        jwt.isAccessToken(legacyAccess) shouldBe true
        jwt.isRefreshToken(legacyAccess) shouldBe false
        jwt.isAccessToken(legacyRefresh) shouldBe false
        jwt.isRefreshToken(legacyRefresh) shouldBe true
    }

    private fun auth(): UserAuthService {
        val users = mock(UserRepository::class.java)
        `when`(users.findByEmail(user.email)).thenReturn(dbUser)
        return UserAuthService(
            users,
            mock(TokenRepository::class.java),
            jwt,
            mock(PasswordService::class.java),
            mock(TokenService::class.java),
            mock(RequestContextFactory::class.java),
            mock(ClientIpResolver::class.java),
            mock(EmailService::class.java),
            StaticMessageSource(),
            "https://www.boat4you.com",
            false,
            Duration.ofMinutes(60),
        )
    }

    private fun bearer(token: String) = mock(HttpServletRequest::class.java).also { `when`(it.getHeader("Authorization")).thenReturn("Bearer $token") }

    @Test
    fun `refreshToken refuses an access token`() {
        assertThrows<InternalLoginException> { auth().refreshToken(bearer(jwt.generateToken(user).first)) }
    }

    @Test
    fun `a malformed or expired token is bad credentials, not a 500 (review 16-11)`() {
        assertThrows<InternalLoginException> { auth().refreshToken(bearer("not-a-jwt")) }
        ReflectionTestUtils.setField(jwt, "refreshExpiration", -1_000L)
        assertThrows<InternalLoginException> { auth().refreshToken(bearer(jwt.generateRefreshToken(user).first)) }
    }
}
