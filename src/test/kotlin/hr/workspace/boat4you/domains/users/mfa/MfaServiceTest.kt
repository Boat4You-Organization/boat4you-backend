package hr.workspace.boat4you.domains.users.mfa

import hr.workspace.boat4you.domains.roles.jpa.RoleAssignmentEntity
import hr.workspace.boat4you.domains.roles.jpa.RoleEntity
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.services.JwtService
import hr.workspace.boat4you.security.services.UserAuthService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.openapitools.model.TokenResponse
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.util.Base64
import java.util.Optional

/**
 * Two-step admin sign-in (Mario 16.11.2026): who gets the second step, the authenticator code, recovery codes and
 * "remember this device". (Passkey signatures are verified by webauthn4j; its ceremony needs a real authenticator.)
 */
class MfaServiceTest {
    private val jwt =
        JwtService().also {
            ReflectionTestUtils.setField(it, "secretKey", Base64.getEncoder().encodeToString(ByteArray(32) { i -> (i * 5 + 1).toByte() }))
            ReflectionTestUtils.setField(it, "jwtExpiration", 86_400_000L)
            ReflectionTestUtils.setField(it, "refreshExpiration", 259_200_000L)
            ReflectionTestUtils.setField(it, "issuer", "boat4you-ws")
            ReflectionTestUtils.setField(it, "audience", "boat4you-web")
        }
    private val user =
        UserEntity().apply {
            id = 7
            email = "mario@example.com"
            name = "Mario"
            surname = "Admin"
        }
    private val auth = mock(UserAuthService::class.java)
    private val users = mock(UserRepository::class.java)
    private val passkeys = mock(UserPasskeyRepository::class.java)
    private val totps = mock(UserTotpRepository::class.java)
    private val recovery = mock(UserRecoveryCodeRepository::class.java)
    private val devices = mock(UserTrustedDeviceRepository::class.java)
    private val http = mock(HttpServletRequest::class.java)
    private val policy = MfaPolicy(passkeys, totps, recovery, devices, requiredForAdmin = false)
    private val mfa =
        MfaService(policy, auth, users, jwt, passkeys, totps, recovery, devices, "boat4you.com", "Boat4You Admin", "https://admin.boat4you.com", 30)
    private val secret = MfaCodes.newTotpSecret()

    init {
        `when`(auth.checkPassword("mario@example.com", "right")).thenReturn(user)
        `when`(auth.completeLogin(user, http)).thenReturn(TokenResponse("access", "refresh", 7))
        `when`(users.findById(7)).thenReturn(Optional.of(user))
        `when`(passkeys.findAllByUserId(7)).thenReturn(emptyList())
        `when`(recovery.findAllByUserIdAndUsedAtIsNull(7)).thenReturn(emptyList())
        `when`(totps.findById(7)).thenReturn(Optional.empty())
    }

    private fun withTotp(lastStep: Long? = null): UserTotp {
        val totp =
            UserTotp().apply {
                userId = 7
                this.secret = this@MfaServiceTest.secret
                this.lastStep = lastStep
            }
        `when`(totps.findById(7)).thenReturn(Optional.of(totp))
        return totp
    }

    @Test
    fun `an account without a second step signs in with the password, as before`() {
        val response = mfa.login(MfaLoginRequest("mario@example.com", "right"), http)

        response.status shouldBe "OK"
        response.token shouldBe "access"
        response.setupRequired shouldBe false
    }

    @Test
    fun `with an authenticator set up the password alone gives only a short step token`() {
        withTotp()

        val response = mfa.login(MfaLoginRequest("mario@example.com", "right"), http)

        response.status shouldBe "MFA_REQUIRED"
        response.token shouldBe null
        response.methods shouldBe listOf("totp")
        val claims = jwt.parsePurposeToken(response.mfaToken, "mfa")!!
        jwt.isAccessToken(claims) shouldBe false
        jwt.isRefreshToken(claims) shouldBe false
    }

    @Test
    fun `the right code signs in and can remember the device, a code is used once, a wrong one counts`() {
        val totp = withTotp()
        val mfaToken = mfa.login(MfaLoginRequest("mario@example.com", "right"), http).mfaToken
        val code = MfaCodes.totpAt(secret, MfaCodes.currentStep())

        val signedIn = mfa.verifyTotp(CodeVerification(mfaToken, code, rememberDevice = true), http)
        signedIn.status shouldBe "OK"
        signedIn.deviceToken shouldNotBe null
        totp.lastStep shouldBe MfaCodes.currentStep()

        assertThrows<InternalLoginException> { mfa.verifyTotp(CodeVerification(mfaToken, code), http) }
        assertThrows<InternalLoginException> { mfa.verifyTotp(CodeVerification(mfaToken, "000000"), http) }
        verify(auth, times(2)).recordFailedSignIn(user)
    }

    @Test
    fun `a remembered device skips the second step until it expires`() {
        withTotp()
        val token = "device-token"
        val device =
            UserTrustedDevice().apply {
                userId = 7
                tokenHash = MfaCodes.sha256Hex(token)
                expiresAt = Instant.now().plusSeconds(3600)
            }
        `when`(devices.findByTokenHash(MfaCodes.sha256Hex(token))).thenReturn(device)

        mfa.login(MfaLoginRequest("mario@example.com", "right", token), http).status shouldBe "OK"

        device.expiresAt = Instant.now().minusSeconds(1)
        mfa.login(MfaLoginRequest("mario@example.com", "right", token), http).status shouldBe "MFA_REQUIRED"
    }

    @Test
    fun `a recovery code works once`() {
        withTotp()
        val code =
            UserRecoveryCode().apply {
                userId = 7
                codeHash = MfaCodes.sha256Hex("abcde23456")
            }
        `when`(recovery.findAllByUserIdAndUsedAtIsNull(7)).thenReturn(listOf(code))
        val mfaToken = mfa.login(MfaLoginRequest("mario@example.com", "right"), http).mfaToken

        mfa.verifyRecoveryCode(CodeVerification(mfaToken, "ABCDE-23456"), http).status shouldBe "OK"
        code.usedAt shouldNotBe null
    }

    @Test
    fun `a step token is not a sign-in - forged, of another type or for someone else is refused`() {
        withTotp()
        assertThrows<InternalLoginException> { mfa.verifyTotp(CodeVerification("not-a-token", "123456"), http) }
        val access = jwt.generatePurposeToken("access", 7, "mario@example.com", emptyMap(), 60_000)
        assertThrows<InternalLoginException> { mfa.verifyTotp(CodeVerification(access, "123456"), http) }
        val otherEmail = jwt.generatePurposeToken("mfa", 7, "someone@example.com", emptyMap(), 60_000)
        assertThrows<InternalLoginException> { mfa.verifyTotp(CodeVerification(otherEmail, "123456"), http) }
    }

    @Test
    fun `phase 2 - an admin with nothing set up signs in with the password and is sent to set it up`() {
        val admin =
            UserEntity().apply {
                id = 7
                email = "mario@example.com"
            }
        admin.roleAssignments.add(
            RoleAssignmentEntity().apply {
                this.user = admin
                role = RoleEntity().apply { name = "SYSTEM_ADMIN" }
            },
        )
        `when`(auth.checkPassword("mario@example.com", "right")).thenReturn(admin)
        `when`(auth.completeLogin(admin, http)).thenReturn(TokenResponse("access", "refresh", 7))
        val strictPolicy = MfaPolicy(passkeys, totps, recovery, devices, requiredForAdmin = true)
        val strict = MfaService(strictPolicy, auth, users, jwt, passkeys, totps, recovery, devices, "boat4you.com", "Boat4You Admin", "https://admin.boat4you.com", 30)

        strictPolicy.isRequired(admin) shouldBe true
        strictPolicy.isRequired(user) shouldBe false
        val response = strict.login(MfaLoginRequest("mario@example.com", "right"), http)
        response.status shouldBe "OK"
        response.setupRequired shouldBe true
    }
}
