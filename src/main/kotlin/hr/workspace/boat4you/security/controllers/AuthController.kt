package hr.workspace.boat4you.security.controllers

import hr.workspace.boat4you.domains.users.services.UserRegistrationService
import hr.workspace.boat4you.security.services.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.openapitools.api.AuthApi
import org.openapitools.model.RequestPasswordResetBody
import org.openapitools.model.SetUserPasswordBody
import org.openapitools.model.TokenResponse
import org.openapitools.model.UpdateUserPasswordBody
import org.openapitools.model.User
import org.openapitools.model.UserEmailVerificationRequest
import org.openapitools.model.UserLoginRequest
import org.openapitools.model.UserRegistrationRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping

@Controller
@Validated
internal class AuthController(
    private val userAuthService: UserAuthService,
    private val userRegistrationService: UserRegistrationService,
    private val httpRequest: HttpServletRequest,
) : AuthApi {
    override fun loginUser(userLoginRequest: UserLoginRequest): ResponseEntity<TokenResponse> {
        return ResponseEntity(
            userAuthService.login(userLoginRequest.email, userLoginRequest.password, httpRequest),
            HttpStatus.OK,
        )
    }

    override fun refreshToken(): ResponseEntity<TokenResponse> {
        return ResponseEntity(
            userAuthService.refreshToken(httpRequest),
            HttpStatus.OK,
        )
    }

    @PreAuthorize("isAuthenticated()")
    override fun logoutUser(): ResponseEntity<Unit> {
        userAuthService.logout(httpRequest)
        return ResponseEntity(HttpStatus.OK)
    }

    @PreAuthorize("isAuthenticated()")
    override fun updatePassword(updateUserPasswordBody: UpdateUserPasswordBody): ResponseEntity<Unit> {
        userAuthService.updateUserPassword(updateUserPasswordBody, httpRequest)
        return ResponseEntity(HttpStatus.OK)
    }

    override fun requestPasswordReset(requestPasswordResetBody: RequestPasswordResetBody): ResponseEntity<Unit> {
        userAuthService.requestPasswordReset(requestPasswordResetBody, httpRequest)
        return ResponseEntity(HttpStatus.OK)
    }

    override fun checkPasswordResetValidity(passwordResetCode: String): ResponseEntity<Unit> {
        userAuthService.checkPasswordResetValidity(passwordResetCode)
        return ResponseEntity(HttpStatus.OK)
    }

    override fun resetPassword(
        passwordResetCode: String,
        setUserPasswordBody: SetUserPasswordBody,
    ): ResponseEntity<Unit> {
        userAuthService.resetPassword(passwordResetCode, setUserPasswordBody)
        return ResponseEntity(HttpStatus.OK)
    }

    @PreAuthorize("isAuthenticated()")
    override fun getAuthenticatedUserInfo(): ResponseEntity<User> {
        return ResponseEntity(userAuthService.getCurrentUser(), HttpStatus.OK)
    }

    override fun registerUser(userRegistrationRequest: UserRegistrationRequest): ResponseEntity<User> {
        return ResponseEntity(userRegistrationService.registerUser(userRegistrationRequest), HttpStatus.OK)
    }

    override fun resendEmailVerificationCode(userId: Long): ResponseEntity<Unit> {
        userRegistrationService.resendEmailVerificationCode(userId)
        return ResponseEntity(HttpStatus.OK)
    }

    override fun verifyEmail(userEmailVerificationRequest: UserEmailVerificationRequest): ResponseEntity<TokenResponse> {
        return ResponseEntity(userRegistrationService.verifyEmail(userEmailVerificationRequest, httpRequest), HttpStatus.OK)
    }
}
