package hr.workspace.boat4you.security.controllers

import hr.workspace.boat4you.security.getAuthenticatedUserId
import hr.workspace.boat4you.security.services.UserAuthService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SetPasswordController(
    private val userAuthService: UserAuthService,
) {
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/users/me/set-password")
    fun setPassword(
        @RequestBody body: SetPasswordBody,
    ) = userAuthService.setInitialPassword(getAuthenticatedUserId(), body.newPassword)
}

data class SetPasswordBody(
    val newPassword: String,
)
