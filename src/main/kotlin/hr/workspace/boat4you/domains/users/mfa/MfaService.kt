package hr.workspace.boat4you.domains.users.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.models.ClaimsConstants
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.services.JwtService
import hr.workspace.boat4you.security.services.UserAuthService
import io.jsonwebtoken.Claims
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-step admin sign-in (Mario 16.11.2026): the password, then a passkey (Face ID / Touch ID), an authenticator-app
 * code or a one-time recovery code; "remember this device" skips the second step for [trustedDeviceDays].
 *
 * Every step token is a short-lived signed JWT (JwtService.generatePurposeToken): "mfa" after the password, a WebAuthn
 * challenge per passkey ceremony (used once). A failed second step counts against the same lockout as a wrong
 * password (5 tries, 15 minutes).
 */
@Service
class MfaService(
    private val policy: MfaPolicy,
    private val userAuthService: UserAuthService,
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
    private val passkeys: UserPasskeyRepository,
    private val totps: UserTotpRepository,
    private val recoveryCodes: UserRecoveryCodeRepository,
    private val trustedDevices: UserTrustedDeviceRepository,
    @Value("\${application.mfa.rp-id:boat4you.com}")
    private val rpId: String,
    @Value("\${application.mfa.rp-name:Boat4You Admin}")
    private val rpName: String,
    @Value("\${application.mfa.origins:https://admin.boat4you.com}")
    origins: String,
    @Value("\${application.mfa.trusted-device-days:30}")
    private val trustedDeviceDays: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val allowedOrigins: Set<Origin> =
        origins
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Origin(it) }
            .toSet()
    private val webAuthn: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val credentialConverter = AttestedCredentialDataConverter(ObjectConverter())

    /** Challenges already answered (single node: cusma2), with their expiry, so a captured answer is not replayed. */
    private val usedChallenges = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ sign-in

    @Transactional
    fun login(
        request: MfaLoginRequest,
        http: HttpServletRequest,
    ): MfaLoginResponse {
        val user = userAuthService.checkPassword(request.email, request.password)
        val userId = user.id!!
        if (!policy.isRequired(user)) return signedIn(user, http, remember = false)
        // Phase 2 and nothing set up yet: signed in, sent to set it up.
        if (!policy.isEnrolled(userId)) return signedIn(user, http, remember = false, setupRequired = true)
        if (isTrustedDevice(userId, request.deviceToken)) return signedIn(user, http, remember = false)

        val mfaToken = jwtService.generatePurposeToken(TYPE_MFA, userId, user.email, emptyMap(), STEP_TTL.toMillis())
        return MfaLoginResponse(status = STATUS_MFA_REQUIRED, mfaToken = mfaToken, methods = methods(userId))
    }

    fun passkeyRequestOptions(mfaToken: String?): PasskeyRequestOptions {
        val user = userOf(jwtService.parsePurposeToken(mfaToken, TYPE_MFA))
        val challenge = MfaCodes.randomBytes(32)
        return PasskeyRequestOptions(
            challenge = MfaCodes.base64Url(challenge),
            rpId = rpId,
            allowCredentials = passkeys.findAllByUserId(user.id!!).map { it.credentialId },
            timeout = CEREMONY_TIMEOUT_MS,
            userVerification = "required",
            challengeToken = challengeToken(TYPE_WEBAUTHN_GET, user, challenge),
        )
    }

    @Transactional
    fun verifyPasskey(
        request: PasskeyAssertion,
        http: HttpServletRequest,
    ): MfaLoginResponse {
        val user = userOf(jwtService.parsePurposeToken(request.mfaToken, TYPE_MFA))
        userAuthService.assertNotLockedOut(user)
        val challenge = takeChallenge(request.challengeToken, TYPE_WEBAUTHN_GET, user) ?: fail(user)
        val passkey = passkeys.findByCredentialId(request.credentialId)?.takeIf { it.userId == user.id } ?: fail(user)

        val record =
            CredentialRecordImpl(
                NoneAttestationStatement(),
                null,
                null,
                null,
                passkey.signCount,
                credentialConverter.convert(passkey.attestedCredentialData),
                null,
                null,
                null,
                null,
            )
        val credentialId = MfaCodes.fromBase64Url(passkey.credentialId)
        val data =
            try {
                webAuthn.verify(
                    AuthenticationRequest(
                        credentialId,
                        request.userHandle?.takeIf { it.isNotBlank() }?.let { MfaCodes.fromBase64Url(it) },
                        MfaCodes.fromBase64Url(request.authenticatorData),
                        MfaCodes.fromBase64Url(request.clientDataJSON),
                        MfaCodes.fromBase64Url(request.signature),
                    ),
                    AuthenticationParameters(serverProperty(challenge), record, listOf(credentialId), true, true),
                )
            } catch (e: Exception) {
                log.warn("Passkey sign-in refused for user {}: {}", user.id, e.message)
                fail(user)
            }
        passkey.signCount = data.authenticatorData?.signCount ?: passkey.signCount
        passkey.lastUsedAt = Instant.now()
        passkeys.save(passkey)
        return signedIn(user, http, request.rememberDevice)
    }

    @Transactional
    fun verifyTotp(
        request: CodeVerification,
        http: HttpServletRequest,
    ): MfaLoginResponse {
        val user = userOf(jwtService.parsePurposeToken(request.mfaToken, TYPE_MFA))
        userAuthService.assertNotLockedOut(user)
        val totp = totps.findById(user.id!!).orElse(null)?.takeIf { it.secret != null } ?: fail(user)
        val step = MfaCodes.matchTotp(totp.secret!!, request.code, totp.lastStep) ?: fail(user)
        totp.lastStep = step
        totps.save(totp)
        return signedIn(user, http, request.rememberDevice)
    }

    @Transactional
    fun verifyRecoveryCode(
        request: CodeVerification,
        http: HttpServletRequest,
    ): MfaLoginResponse {
        val user = userOf(jwtService.parsePurposeToken(request.mfaToken, TYPE_MFA))
        userAuthService.assertNotLockedOut(user)
        val hash = MfaCodes.sha256Hex(MfaCodes.normaliseRecoveryCode(request.code))
        val code = recoveryCodes.findAllByUserIdAndUsedAtIsNull(user.id!!).firstOrNull { it.codeHash == hash } ?: fail(user)
        code.usedAt = Instant.now()
        recoveryCodes.save(code)
        return signedIn(user, http, request.rememberDevice)
    }

    // ------------------------------------------------------------------ settings (signed-in user)

    @Transactional(readOnly = true)
    fun status(userId: Long): MfaStatus {
        val user = userRepository.findById(userId).orElseThrow { UserDoesNotExistException() }
        val totp = totps.findById(userId).orElse(null)
        return MfaStatus(
            passkeys =
                passkeys.findAllByUserId(userId).sortedBy { it.createdAt }.map {
                    PasskeyInfo(it.id!!, it.label, it.createdAt, it.lastUsedAt)
                },
            totpEnabled = totp?.secret != null,
            recoveryCodesLeft = recoveryCodes.findAllByUserIdAndUsedAtIsNull(userId).size,
            required = policy.isRequired(user),
            enrolled = policy.isEnrolled(userId),
        )
    }

    fun passkeyCreationOptions(userId: Long): PasskeyCreationOptions {
        val user = userRepository.findById(userId).orElseThrow { UserDoesNotExistException() }
        val challenge = MfaCodes.randomBytes(32)
        return PasskeyCreationOptions(
            challenge = MfaCodes.base64Url(challenge),
            rp = mapOf("id" to rpId, "name" to rpName),
            user =
                mapOf(
                    "id" to MfaCodes.base64Url(ByteBuffer.allocate(8).putLong(userId).array()),
                    "name" to user.email,
                    "displayName" to user.getFullName().trim().ifBlank { user.email },
                ),
            pubKeyCredParams = SUPPORTED_ALGORITHMS.map { mapOf("type" to "public-key", "alg" to it.value) },
            excludeCredentials = passkeys.findAllByUserId(userId).map { it.credentialId },
            timeout = CEREMONY_TIMEOUT_MS,
            challengeToken = challengeToken(TYPE_WEBAUTHN_CREATE, user, challenge),
        )
    }

    @Transactional
    fun registerPasskey(
        userId: Long,
        request: PasskeyRegistration,
    ): PasskeyInfo {
        val user = userRepository.findById(userId).orElseThrow { UserDoesNotExistException() }
        val challenge = takeChallenge(request.challengeToken, TYPE_WEBAUTHN_CREATE, user) ?: invalid("challengeToken")
        val data =
            try {
                webAuthn.verify(
                    RegistrationRequest(
                        MfaCodes.fromBase64Url(request.attestationObject),
                        MfaCodes.fromBase64Url(request.clientDataJSON),
                        request.transports.orEmpty().toSet(),
                    ),
                    RegistrationParameters(
                        serverProperty(challenge),
                        SUPPORTED_ALGORITHMS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) },
                        true,
                        true,
                    ),
                )
            } catch (e: Exception) {
                log.warn("Passkey registration refused for user {}: {}", userId, e.message)
                invalid("passkey")
            }
        val authenticatorData = data.attestationObject?.authenticatorData ?: invalid("passkey")
        val credential = authenticatorData.attestedCredentialData ?: invalid("passkey")
        val passkey =
            passkeys.save(
                UserPasskey().apply {
                    this.userId = userId
                    credentialId = MfaCodes.base64Url(credential.credentialId)
                    attestedCredentialData = credentialConverter.convert(credential)
                    signCount = authenticatorData.signCount
                    label = request.label?.trim()?.take(100)?.ifBlank { null }
                },
            )
        return PasskeyInfo(passkey.id!!, passkey.label, passkey.createdAt, passkey.lastUsedAt)
    }

    @Transactional
    fun deletePasskey(
        userId: Long,
        passkeyId: Long,
    ) {
        val passkey = passkeys.findById(passkeyId).orElse(null)?.takeIf { it.userId == userId } ?: invalid("passkeyId")
        passkeys.delete(passkey)
    }

    @Transactional
    fun totpSetup(userId: Long): TotpSetup {
        val user = userRepository.findById(userId).orElseThrow { UserDoesNotExistException() }
        val totp = totps.findById(userId).orElse(null) ?: UserTotp().apply { this.userId = userId }
        val secret = MfaCodes.newTotpSecret()
        totp.pendingSecret = secret
        totps.save(totp)
        return TotpSetup(secret = secret, otpauthUri = MfaCodes.otpauthUri(secret, user.email, rpName))
    }

    @Transactional
    fun totpConfirm(
        userId: Long,
        code: String,
    ) {
        val totp = totps.findById(userId).orElse(null)?.takeIf { it.pendingSecret != null } ?: invalid("setup")
        val step = MfaCodes.matchTotp(totp.pendingSecret!!, code, null) ?: invalid("code")
        totp.secret = totp.pendingSecret
        totp.pendingSecret = null
        totp.enabledAt = Instant.now()
        totp.lastStep = step
        totps.save(totp)
    }

    @Transactional
    fun totpDisable(userId: Long) {
        totps.findById(userId).orElse(null)?.let { totps.delete(it) }
    }

    /** Ten new one-time codes, shown once; the old ones stop working. */
    @Transactional
    fun newRecoveryCodes(userId: Long): List<String> {
        recoveryCodes.deleteAllByUserId(userId)
        val codes = (1..RECOVERY_CODE_COUNT).map { MfaCodes.newRecoveryCode() }
        recoveryCodes.saveAll(
            codes.map { code ->
                UserRecoveryCode().apply {
                    this.userId = userId
                    codeHash = MfaCodes.sha256Hex(MfaCodes.normaliseRecoveryCode(code))
                }
            },
        )
        return codes
    }

    @Transactional
    fun forgetTrustedDevices(userId: Long) {
        trustedDevices.deleteAllByUserId(userId)
    }

    // ------------------------------------------------------------------ internals

    private fun methods(userId: Long): List<String> =
        listOfNotNull(
            "passkey".takeIf { policy.hasPasskey(userId) },
            "totp".takeIf { policy.hasTotp(userId) },
            "recovery".takeIf { recoveryCodes.findAllByUserIdAndUsedAtIsNull(userId).isNotEmpty() },
        )

    private fun signedIn(
        user: UserEntity,
        http: HttpServletRequest,
        remember: Boolean,
        setupRequired: Boolean = false,
    ): MfaLoginResponse {
        val tokens = userAuthService.completeLogin(user, http)
        val deviceToken = if (remember) rememberDevice(user.id!!, http.getHeader("User-Agent")) else null
        return MfaLoginResponse(
            status = STATUS_OK,
            token = tokens.token,
            refreshToken = tokens.refreshToken,
            userId = tokens.userId,
            deviceToken = deviceToken,
            setupRequired = setupRequired,
        )
    }

    private fun rememberDevice(
        userId: Long,
        userAgent: String?,
    ): String {
        val token = MfaCodes.base64Url(MfaCodes.randomBytes(32))
        trustedDevices.save(
            UserTrustedDevice().apply {
                this.userId = userId
                tokenHash = MfaCodes.sha256Hex(token)
                this.userAgent = userAgent?.take(255)
                expiresAt = Instant.now().plus(Duration.ofDays(trustedDeviceDays))
            },
        )
        return token
    }

    private fun isTrustedDevice(
        userId: Long,
        deviceToken: String?,
    ): Boolean {
        if (deviceToken.isNullOrBlank()) return false
        val device = trustedDevices.findByTokenHash(MfaCodes.sha256Hex(deviceToken)) ?: return false
        return device.userId == userId && device.expiresAt.isAfter(Instant.now())
    }

    private fun challengeToken(
        type: String,
        user: UserEntity,
        challenge: ByteArray,
    ): String = jwtService.generatePurposeToken(type, user.id!!, user.email, mapOf(CLAIM_CHALLENGE to MfaCodes.base64Url(challenge)), STEP_TTL.toMillis())

    /** The challenge of a valid, unused [type] token issued to [user]; marked used. Null otherwise. */
    private fun takeChallenge(
        token: String?,
        type: String,
        user: UserEntity,
    ): ByteArray? {
        val claims = jwtService.parsePurposeToken(token, type) ?: return null
        if (claims[ClaimsConstants.USER_ID] != user.id.toString()) return null
        val challenge = claims[CLAIM_CHALLENGE] as? String ?: return null
        val now = System.currentTimeMillis()
        usedChallenges.entries.removeIf { it.value < now }
        if (usedChallenges.putIfAbsent(challenge, claims.expiration.time) != null) return null
        return MfaCodes.fromBase64Url(challenge)
    }

    private fun serverProperty(challenge: ByteArray) = ServerProperty(allowedOrigins, rpId, DefaultChallenge(challenge))

    private fun userOf(claims: Claims?): UserEntity {
        val id = (claims?.get(ClaimsConstants.USER_ID) as? String)?.toLongOrNull()
        val user = id?.let { userRepository.findById(it).orElse(null) }
        if (user == null || user.email != claims.subject) {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, "unknown email")
        }
        return user
    }

    /** A failed second step: counts against the lockout (InternalLoginException does not roll back). */
    private fun fail(user: UserEntity): Nothing {
        userAuthService.recordFailedSignIn(user)
        throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, user.email)
    }

    private fun invalid(parameter: String): Nothing = throw ParameterValidationException(mapOf(parameter to "invalid"))

    companion object {
        const val TYPE_MFA = "mfa"
        const val TYPE_WEBAUTHN_GET = "webauthn-get"
        const val TYPE_WEBAUTHN_CREATE = "webauthn-create"
        const val CLAIM_CHALLENGE = "challenge"
        const val STATUS_OK = "OK"
        const val STATUS_MFA_REQUIRED = "MFA_REQUIRED"
        const val RECOVERY_CODE_COUNT = 10
        const val CEREMONY_TIMEOUT_MS = 120_000L
        val STEP_TTL: Duration = Duration.ofMinutes(5)
        val SUPPORTED_ALGORITHMS = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256)
    }
}
