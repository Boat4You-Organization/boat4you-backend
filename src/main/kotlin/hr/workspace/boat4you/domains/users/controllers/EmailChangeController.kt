package hr.workspace.boat4you.domains.users.controllers

import hr.workspace.boat4you.security.services.UserAuthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Verified email change (option B — short-lived signed token, no DB state).
 *
 * - `POST /users/me/request-email-change` — authenticated (not under /public, so Security
 *   requires a Bearer token). Validates the new address and emails a confirmation link to it.
 * - `POST /public/users/confirm-email-change` — public: the signed token IS the authorization
 *   (carries userId + currentEmail + newEmail), so the link works even when the user clicks it
 *   on a device where they aren't logged in. Public paths are allow-listed in SecurityConfiguration.
 *
 * Both are path-matched by Spring Security independently of living in the same controller.
 */
@RestController
class EmailChangeController(
    private val userAuthService: UserAuthService,
) {
    @PostMapping("/users/me/request-email-change")
    fun requestEmailChange(
        @RequestBody request: RequestEmailChangeBody,
    ): ResponseEntity<Unit> {
        userAuthService.requestEmailChange(request.newEmail)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/public/users/confirm-email-change")
    fun confirmEmailChange(
        @RequestBody request: ConfirmEmailChangeBody,
    ): ResponseEntity<Unit> {
        userAuthService.confirmEmailChange(request.token)
        return ResponseEntity.noContent().build()
    }
}

data class RequestEmailChangeBody(
    val newEmail: String,
)

data class ConfirmEmailChangeBody(
    val token: String,
)
