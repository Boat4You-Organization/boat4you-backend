package hr.workspace.boat4you.security

import hr.workspace.boat4you.common.models.ClaimsConstants
import hr.workspace.boat4you.common.models.WebRequestContext
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.RequestContextFactory
import hr.workspace.boat4you.domains.users.services.toDomainUser
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.services.JwtService
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder.getContext
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val userRepository: UserRepository,
    private val tokenRepository: TokenRepository,
    private val requestContextFactory: RequestContextFactory,
    private val jwtService: JwtService,
) : OncePerRequestFilter() {
    private val log: Logger = LoggerFactory.getLogger(this::class.java.name)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (getContext().authentication == null) {
            val token =
                request
                    .getHeader(AUTHORIZATION)
                    ?.removePrefix("Bearer")
                    ?.trim()
            if (token.isNullOrBlank()) {
                getContext().authentication = AnonymousAuthenticationToken("anonymousKey", "anonymousUser", listOf(SimpleGrantedAuthority("ANONYMOUS")))
            } else {
                try {
                    authorize(request, token)
                } catch (e: JwtException) {
                    log.warn("JWT Error: ${e.message} for URL: ${request.requestURI}")
                }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun authorize(
        httpRequest: HttpServletRequest,
        token: String,
    ) {
        val claims = jwtService.extractAllClaims(token)
        val userEmail = claims[ClaimsConstants.USER_EMAIL] as String?

        if (userEmail == null) {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, "unknown email")
        }

        val dbUser = userRepository.findByEmail(userEmail) ?: throw InternalLoginException(InternalLoginException.Type.USER_DOES_NOT_EXIST, userEmail)
        val userDomainEntity = dbUser.toDomainUser()
        val dbToken = tokenRepository.findByValue(token)
        val isTokenValid = !(dbToken == null || dbToken.isExpired || dbToken.isRevoked)

        if (isTokenValid && jwtService.isTokenValid(token, userDomainEntity)) {
            // F1-005: authorities come from `userDomainEntity` (built
            // from the freshly-loaded `dbUser.roleAssignments`), NOT
            // from the JWT claims. The previous claim-sourced version
            // meant a user whose role was revoked still had effective
            // admin until their token expired. Re-fetching keeps the
            // role assignment authoritative on the server side; the
            // claims are now informational only.
            getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    userDomainEntity,
                    null,
                    userDomainEntity.authorities,
                )
        }

        val userContext = requestContextFactory.buildRequestContext(userDomainEntity)
        httpRequest.setAttribute(WebRequestContext.CONTEXT_ATTRIBUTE, userContext)
    }
}
