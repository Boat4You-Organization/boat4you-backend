package hr.workspace.boat4you.security.controllers

import hr.workspace.boat4you.security.dto.SessionDto
import hr.workspace.boat4you.security.getAuthenticatedUserId
import hr.workspace.boat4you.security.services.SessionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SessionController(
    private val sessionService: SessionService,
    private val httpRequest: HttpServletRequest,
) {
    private fun currentToken(): String? = httpRequest.getHeader(AUTHORIZATION)?.removePrefix("Bearer")?.trim()

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/users/me/sessions")
    fun listSessions(): List<SessionDto> = sessionService.listSessions(getAuthenticatedUserId(), currentToken())

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/users/me/sessions/{sessionGroup}")
    fun revokeSession(
        @PathVariable sessionGroup: String,
    ) = sessionService.revokeSession(getAuthenticatedUserId(), sessionGroup)

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/users/me/sessions/revoke-others")
    fun revokeOthers() = sessionService.revokeOtherSessions(getAuthenticatedUserId(), currentToken())
}
