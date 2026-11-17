package hr.workspace.boat4you.security

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.RequestContextFactory
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.services.JwtService
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.util.ReflectionTestUtils
import java.util.Base64

/**
 * Security review 17.11.2026: a valid access token of a user deleted or deactivated since made the filter throw
 * (InternalLoginException from findByEmail), so the request ended in a 500. Now it goes on unauthenticated and the
 * security chain answers 401/403.
 */
class JwtFilterUnknownUserTest {
    private val jwt =
        JwtService().also {
            ReflectionTestUtils.setField(it, "secretKey", Base64.getEncoder().encodeToString(ByteArray(32) { i -> (i * 11 + 7).toByte() }))
            ReflectionTestUtils.setField(it, "jwtExpiration", 86_400_000L)
            ReflectionTestUtils.setField(it, "refreshExpiration", 259_200_000L)
            ReflectionTestUtils.setField(it, "issuer", "boat4you-ws")
            ReflectionTestUtils.setField(it, "audience", "boat4you-web")
        }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `a token of a user who is gone leaves the request unauthenticated instead of failing`() {
        val gone =
            UserEntity().apply {
                id = 9
                email = "gone@example.com"
            }
        val token = jwt.generateToken(UserDomainEntity(gone)).first
        // The repository mock finds nobody, as findByEmail does for a deleted or deactivated account.
        val filter = JwtAuthenticationFilter(mock(UserRepository::class.java), mock(TokenRepository::class.java), mock(RequestContextFactory::class.java), jwt)
        val request = MockHttpServletRequest("GET", "/users/me").apply { addHeader("Authorization", "Bearer $token") }
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, MockHttpServletResponse(), chain)

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())
        (SecurityContextHolder.getContext().authentication == null) shouldBe true
    }
}
