package hr.workspace.boat4you.security.controllers

import hr.workspace.boat4you.security.services.OAuthService
import jakarta.servlet.http.HttpServletRequest
import org.openapitools.model.TokenResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Social login endpoints. Kept separate from the OpenAPI-generated
 * AuthController (which is bound to the spec) — same plain-@RestController
 * pattern as EmailChangeController. Public: the request carries its own proof of
 * identity (a provider-signed ID token) and is permitted in
 * SecurityConfiguration under the /auth/oauth/ path. On success it returns the
 * same TokenResponse (access + refresh) as a password login.
 */
@RestController
class OAuthController(
    private val oAuthService: OAuthService,
    private val httpRequest: HttpServletRequest,
) {
    @PostMapping("/auth/oauth/google")
    fun loginWithGoogle(
        @RequestBody body: GoogleLoginBody,
    ): TokenResponse = oAuthService.loginWithGoogle(body.idToken, httpRequest)
}

data class GoogleLoginBody(
    val idToken: String,
)
