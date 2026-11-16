package hr.workspace.boat4you.domains.users.mfa

import com.fasterxml.jackson.annotation.JsonInclude
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// Two-step admin sign-in (Mario 16.11.2026). Binary WebAuthn fields travel as base64url strings, as the browser's
// PublicKeyCredential gives them (the admin converts ArrayBuffers both ways).

data class MfaLoginRequest(
    val email: String,
    val password: String,
    /** "Remember this device" token from an earlier sign-in in this browser. */
    val deviceToken: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MfaLoginResponse(
    /** "OK" (signed in) or "MFA_REQUIRED" (send one of [methods] with [mfaToken]). */
    val status: String,
    val token: String? = null,
    val refreshToken: String? = null,
    val userId: Long? = null,
    val mfaToken: String? = null,
    /** "passkey", "totp", "recovery". */
    val methods: List<String>? = null,
    /** Set when the browser asked to be remembered: keep it and send it with the next sign-in. */
    val deviceToken: String? = null,
    /** Phase 2 and nothing set up yet: the admin opens the security settings first. */
    val setupRequired: Boolean = false,
)

data class MfaTokenRequest(
    val mfaToken: String? = null,
)

data class PasskeyRequestOptions(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<String>,
    val timeout: Long,
    val userVerification: String,
    val challengeToken: String,
)

data class PasskeyAssertion(
    val mfaToken: String? = null,
    val challengeToken: String? = null,
    val credentialId: String = "",
    val clientDataJSON: String = "",
    val authenticatorData: String = "",
    val signature: String = "",
    val userHandle: String? = null,
    val rememberDevice: Boolean = false,
)

data class CodeVerification(
    val mfaToken: String? = null,
    val code: String = "",
    val rememberDevice: Boolean = false,
)

data class PasskeyCreationOptions(
    val challenge: String,
    val rp: Map<String, String>,
    val user: Map<String, String>,
    val pubKeyCredParams: List<Map<String, Any>>,
    val excludeCredentials: List<String>,
    val timeout: Long,
    val challengeToken: String,
)

data class PasskeyRegistration(
    val challengeToken: String? = null,
    val clientDataJSON: String = "",
    val attestationObject: String = "",
    val transports: List<String>? = null,
    val label: String? = null,
)

data class PasskeyInfo(
    val id: Long,
    val label: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

data class MfaStatus(
    val passkeys: List<PasskeyInfo>,
    val totpEnabled: Boolean,
    val recoveryCodesLeft: Int,
    /** This account must pass the second step at sign-in. */
    val required: Boolean,
    /** A passkey or an authenticator app is set up. */
    val enrolled: Boolean,
)

data class TotpSetup(
    val secret: String,
    val otpauthUri: String,
)

data class TotpCode(
    val code: String = "",
)

/** Public (SecurityConfiguration permits the /auth/mfa paths): every call checks its own short-lived token. */
@RestController
@RequestMapping("/auth/mfa")
class MfaAuthController(
    private val mfa: MfaService,
    private val http: HttpServletRequest,
) {
    @PostMapping("/login")
    fun login(
        @RequestBody request: MfaLoginRequest,
    ): MfaLoginResponse = mfa.login(request, http)

    @PostMapping("/passkey/options")
    fun passkeyOptions(
        @RequestBody request: MfaTokenRequest,
    ): PasskeyRequestOptions = mfa.passkeyRequestOptions(request.mfaToken)

    @PostMapping("/passkey/verify")
    fun passkeyVerify(
        @RequestBody request: PasskeyAssertion,
    ): MfaLoginResponse = mfa.verifyPasskey(request, http)

    @PostMapping("/totp/verify")
    fun totpVerify(
        @RequestBody request: CodeVerification,
    ): MfaLoginResponse = mfa.verifyTotp(request, http)

    @PostMapping("/recovery/verify")
    fun recoveryVerify(
        @RequestBody request: CodeVerification,
    ): MfaLoginResponse = mfa.verifyRecoveryCode(request, http)
}

/** The signed-in user's own second-step settings. */
@RestController
@RequestMapping("/users/me/mfa")
@PreAuthorize("isAuthenticated()")
class MfaSettingsController(
    private val mfa: MfaService,
) {
    private fun me(): Long = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID } ?: throw AccessDeniedException("Access denied")

    @GetMapping
    fun status(): MfaStatus = mfa.status(me())

    @PostMapping("/passkey/options")
    fun passkeyOptions(): PasskeyCreationOptions = mfa.passkeyCreationOptions(me())

    @PostMapping("/passkey")
    fun registerPasskey(
        @RequestBody request: PasskeyRegistration,
    ): PasskeyInfo = mfa.registerPasskey(me(), request)

    @DeleteMapping("/passkey/{id}")
    fun deletePasskey(
        @PathVariable id: Long,
    ) = mfa.deletePasskey(me(), id)

    @PostMapping("/totp/setup")
    fun totpSetup(): TotpSetup = mfa.totpSetup(me())

    @PostMapping("/totp/confirm")
    fun totpConfirm(
        @RequestBody request: TotpCode,
    ) = mfa.totpConfirm(me(), request.code)

    @DeleteMapping("/totp")
    fun totpDisable() = mfa.totpDisable(me())

    @PostMapping("/recovery-codes")
    fun recoveryCodes(): List<String> = mfa.newRecoveryCodes(me())

    @DeleteMapping("/trusted-devices")
    fun forgetTrustedDevices() = mfa.forgetTrustedDevices(me())
}
