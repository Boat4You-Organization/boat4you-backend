package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.models.WebRequestContext
import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.RequestContextFactory
import hr.workspace.boat4you.domains.users.services.toDomainUser
import hr.workspace.boat4you.domains.users.services.toUserModel
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import hr.workspace.boat4you.security.exceptions.PasswordException
import hr.workspace.boat4you.security.getAuthenticatedUserId
import hr.workspace.boat4you.security.getContextOptional
import hr.workspace.boat4you.security.getContextRequired
import hr.workspace.boat4you.security.jpa.TokenEntity
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.jpa.TokenService
import jakarta.servlet.http.HttpServletRequest
import org.bouncycastle.util.encoders.Base64
import org.openapitools.model.RequestPasswordResetBody
import org.openapitools.model.SetUserPasswordBody
import org.openapitools.model.TokenResponse
import org.openapitools.model.UpdateUserPasswordBody
import org.openapitools.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

private const val MAX_LOGIN_ATTEMPTS_COUNT = 5
private const val MAX_LOGIN_ATTEMPTS_LOCK_PERIOD = 15 * 60L // 15 minutes

@Service
class UserAuthService(
    private val userRepository: UserRepository,
    private val tokenRepository: TokenRepository,
    private val jwtService: JwtService,
    private val passwordService: PasswordService,
    private val tokenService: TokenService,
    private val requestContextFactory: RequestContextFactory,
    private val clientIpResolver: ClientIpResolver,
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
    @Value("\${application.invites.user.allow-uninvited-login}")
    private val allowUninvitedLogin: Boolean,
    @Value("\${application.password-reset.expiration:60m}")
    private val passwordResetExpiration: Duration,
) {
    private val secureRandom = SecureRandom.getInstance("SHA1PRNG")

    @Transactional(readOnly = false)
    fun login(
        email: String,
        password: String,
        httpRequest: HttpServletRequest,
    ): TokenResponse {
        val dbUser = userRepository.findByEmail(email) ?: throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, email)

        if (dbUser.registrationStatus != UserRegistrationStatusEnum.REGISTERED) {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, email)
        }

        if (!allowUninvitedLogin && dbUser.inviteStatus != UserInviteStatusEnum.ACCEPTED) {
            throw InternalLoginException(InternalLoginException.Type.USER_INVITE_NOT_ACCEPTED, email)
        }

        if (dbUser.loginAttempts >= MAX_LOGIN_ATTEMPTS_COUNT &&
            dbUser.lastUnsuccessfulLogin?.plusSeconds(MAX_LOGIN_ATTEMPTS_LOCK_PERIOD)?.isAfter(Instant.now()) == true
        ) {
            dbUser.loginAttempts++
            dbUser.lastUnsuccessfulLogin = Instant.now()
            throw InternalLoginException(InternalLoginException.Type.MAX_ATTEMPTS_EXCEEDED, email)
        }

        dbUser.lastUnsuccessfulLogin?.let {
            if (it.plusSeconds(MAX_LOGIN_ATTEMPTS_LOCK_PERIOD).isBefore(Instant.now())) {
                dbUser.loginAttempts = 0
                dbUser.lastUnsuccessfulLogin = null
            }
        }

        if (passwordService.doesMatch(password, dbUser.password).not()) {
            dbUser.loginAttempts++
            dbUser.lastUnsuccessfulLogin = Instant.now()
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, email) // Will not cause rollback, not a RuntimeException
        }

        // Reset failed-login counters on success + record login moment.
        // `lastLoginAt` is surfaced on /my-profile so the customer can spot
        // unfamiliar sessions; resetting attempts/timestamp keeps the lockout
        // window from triggering for the next legitimate login.
        dbUser.loginAttempts = 0
        dbUser.lastUnsuccessfulLogin = null
        dbUser.lastLoginAt = Instant.now()

        val userDomainEntity = dbUser.toDomainUser()
        val userContext = requestContextFactory.buildRequestContext(userDomainEntity)
        httpRequest.setAttribute(WebRequestContext.CONTEXT_ATTRIBUTE, userContext)

        val jwtToken = jwtService.generateToken(userDomainEntity)
        val refreshToken = jwtService.generateRefreshToken(userDomainEntity)

        val sessionGroup = java.util.UUID.randomUUID().toString()
        saveUserToken(dbUser, jwtToken.first, jwtToken.second, httpRequest, sessionGroup)
        saveUserToken(dbUser, refreshToken.first, refreshToken.second, httpRequest, sessionGroup)

        return TokenResponse(
            token = jwtToken.first,
            refreshToken = refreshToken.first,
            userId = dbUser.id!!,
        )
    }

    @Transactional(readOnly = false)
    fun issueTokenAtRegistration(
        dbUser: UserEntity,
        httpRequest: HttpServletRequest,
    ): TokenResponse {
        val userDomainEntity = dbUser.toDomainUser()
        val userContext = requestContextFactory.buildRequestContext(userDomainEntity)
        httpRequest.setAttribute(WebRequestContext.CONTEXT_ATTRIBUTE, userContext)

        val jwtToken = jwtService.generateToken(userDomainEntity)
        val refreshToken = jwtService.generateRefreshToken(userDomainEntity)

        val sessionGroup = java.util.UUID.randomUUID().toString()
        saveUserToken(dbUser, jwtToken.first, jwtToken.second, httpRequest, sessionGroup)
        saveUserToken(dbUser, refreshToken.first, refreshToken.second, httpRequest, sessionGroup)

        return TokenResponse(
            token = jwtToken.first,
            refreshToken = refreshToken.first,
            userId = dbUser.id!!,
        )
    }

    @Transactional(readOnly = false)
    fun logout(httpRequest: HttpServletRequest) {
        val token =
            httpRequest
                .getHeader(AUTHORIZATION)
                ?.removePrefix("Bearer")
                ?.trim()

        if (token.isNullOrBlank()) {
            return
        }

        val storedToken = tokenRepository.findByValue(token)
        if (storedToken != null) {
            // Revoke ALL tokens (access + refresh) for this user, not just
            // the current access token. Previously only the presented token
            // was revoked, so a stolen refresh token could still obtain new
            // access tokens after the user "logged out".
            tokenService.revokeAllUserTokens(storedToken.user.id!!)
            SecurityContextHolder.clearContext()
        }
    }

    @Transactional(readOnly = false)
    fun updateUserPassword(
        body: UpdateUserPasswordBody,
        httpRequest: HttpServletRequest,
    ) {
        val currentUser = httpRequest.getContextRequired()
        val currentUserId = currentUser.currentUserId

        val dbUser = userRepository.findById(currentUserId).getOrElse { throw UserDoesNotExistException() }

        if (!passwordService.doesMatch(body.oldPassword, dbUser.password)) {
            throw PasswordException(PasswordException.PasswordExceptionType.OLD_PASSWORD_INVALID)
        }

        PasswordPolicy.validate(body.newPassword)

        dbUser.password = passwordService.encodePassword(body.newPassword)
        tokenService.revokeAllUserTokens(dbUser.id!!)
    }

    @Transactional(readOnly = false)
    fun refreshToken(httpRequest: HttpServletRequest): TokenResponse {
        val token =
            httpRequest
                .getHeader(AUTHORIZATION)
                ?.removePrefix("Bearer")
                ?.trim()

        if (token.isNullOrBlank()) {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, "unknown email")
        }

        // TODO Catch all exceptions possibly thrown by this method and handle them
        val userEmail: String = jwtService.extractEmail(token)

        val dbUser = userRepository.findByEmail(userEmail) ?: throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, userEmail)
        val userDomainEntity = dbUser.toDomainUser()
        val dbToken = tokenRepository.findByValue(token)
        val isTokenValid = !(dbToken == null || dbToken.isExpired || dbToken.isRevoked)
        if (isTokenValid && jwtService.isTokenValid(token, userDomainEntity)) {
            val accessToken = jwtService.generateToken(userDomainEntity)
            // Reuse the presented refresh token's session_group so the new access
            // token stays grouped under the same device entry (fall back to a new
            // group only for legacy refresh tokens minted before the column existed).
            val sessionGroup = dbToken?.sessionGroup ?: java.util.UUID.randomUUID().toString()
            saveUserToken(dbUser, accessToken.first, accessToken.second, httpRequest, sessionGroup)

            return TokenResponse(
                token = accessToken.first,
                refreshToken = token,
                dbUser.id!!,
            )
        } else {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, userEmail)
        }
    }

    @Transactional(readOnly = false)
    fun requestPasswordReset(
        requestPasswordResetBody: RequestPasswordResetBody,
        httpRequest: HttpServletRequest,
    ) {
        val context = httpRequest.getContextOptional()

        if (context == null && requestPasswordResetBody.email == null) {
            throw ParameterValidationException(mapOf("email" to "Email not provided"))
        }
        if (context != null && requestPasswordResetBody.userId == null) {
            throw ParameterValidationException(mapOf("userId" to "UserId not provided"))
        }

        if (context != null && context.currentUser.authorities.any { it.authority in listOf("SYSTEM_ADMIN", "MANAGER") }.not()) {
            throw AccessDeniedException("Access denied")
        }

        val dbUser =
            if (context == null) {
                // Anonymous user requesting password reset
                userRepository.findByEmail(requestPasswordResetBody.email!!)
            } else {
                // Manager or System admin requesting password reset for someone else
                userRepository.findById(requestPasswordResetBody.userId!!).getOrNull()
            }

        // Account-enumeration defence (OWASP Forgot Password cheatsheet):
        // anonymous callers must get an identical response whether the email
        // exists or not — otherwise an attacker can probe which addresses
        // are registered. Admin callers (context != null) still see the
        // error so they can spot typos / bad userIds in the admin UI.
        if (dbUser == null) {
            if (context != null) {
                throw UserDoesNotExistException()
            }
            return
        }

        dbUser.passwordResetCode = Base64.toBase64String(generateRandomBytes())
        dbUser.passwordResetCodeIssuedAt = Instant.now()
        val passwordResetLink = generatePasswordResetLink(dbUser.passwordResetCode!!)

        // Inbox-friendly recipient: render as `Mario Kuzmanic <email>` when
        // we have a name; bare email otherwise. Mario rule (3.5.2026): all
        // client-facing emails carry full name in the To: header.
        val fullName =
            dbUser.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${dbUser.email}>" else dbUser.email

        // Localize per recipient — user.language captured at first contact
        // (booking flow stamps it from front-end Accept-Language). Falls
        // back to English when null. Admin-driven password reset (manager
        // resetting another user's password) honours the recipient's
        // language, not the admin's — the email goes to the recipient.
        val locale = resolveEmailLocale(dbUser.language)
        val subject = messageSource.getMessage("passwordReset.subject", null, locale)

        val variables =
            mapOf(
                "fullName" to fullName,
                "passwordResetUrl" to passwordResetLink,
                "publicUrl" to serverHostPublic,
                "currentYear" to java.time.LocalDate.now().year.toString(),
            )

        emailService.sendEmail(
            recipients = listOf(recipientAddress),
            subject = subject,
            templateName = "email/passwordReset",
            variables = variables,
            locale = locale,
        )
    }

    private fun generateRandomBytes(length: Int = 64): ByteArray {
        val randomBytes = ByteArray(length)
        secureRandom.nextBytes(randomBytes)
        return randomBytes
    }

    fun checkPasswordResetValidity(passwordResetCode: String): UserEntity {
        val dbUser = userRepository.findByPasswordResetCode(passwordResetCode)
            ?: throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_RESET_INVALID)

        // Token TTL (OWASP Forgot Password cheatsheet). Reject codes older
        // than the configured window so a leaked / forgotten link can't
        // be replayed days later. Tokens issued before the column existed
        // (legacy rows where issuedAt is null) are treated as expired —
        // the user simply requests a fresh reset.
        val issuedAt = dbUser.passwordResetCodeIssuedAt
        if (issuedAt == null || issuedAt.plus(passwordResetExpiration).isBefore(Instant.now())) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_RESET_INVALID)
        }

        return dbUser
    }

    @Transactional(readOnly = false)
    fun resetPassword(
        passwordResetCode: String,
        userPasswordBody: SetUserPasswordBody,
    ) {
        val dbUser = checkPasswordResetValidity(passwordResetCode)

        PasswordPolicy.validate(userPasswordBody.password)

        dbUser.apply {
            password = passwordService.encodePassword(userPasswordBody.password)
            this.passwordResetCode = null
            this.passwordResetCodeIssuedAt = null
            // Activation-via-reset: a guest who skipped the original invite
            // email (link expired after 7d) lands here when they hit
            // "Forgot password" later. Without this flip, login enforces
            // registrationStatus = REGISTERED and locks them out despite
            // a valid password. Successfully responding to a reset email
            // proves ownership of the inbox — treat it as completion of
            // activation.
            if (registrationStatus == UserRegistrationStatusEnum.STARTED) {
                registrationStatus = UserRegistrationStatusEnum.REGISTERED
                inviteStatus = UserInviteStatusEnum.ACCEPTED
                inviteCode = null
                inviteTime = null
            }
        }
    }

    @Transactional(readOnly = true)
    fun getCurrentUser(): User {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }

        if (user == null) {
            throw AccessDeniedException("User is not authenticated")
        }

        return user.toUserModel()
    }

    private fun saveUserToken(
        dbUser: UserEntity,
        jwtToken: String,
        expiresAt: Date,
        httpRequest: HttpServletRequest,
        sessionGroup: String,
    ) {
        val token =
            TokenEntity().apply {
                value = jwtToken
                user = dbUser
                this.expiresAt = expiresAt.toInstant()
                this.sessionGroup = sessionGroup
                userAgent = httpRequest.getHeader("User-Agent")?.take(512)
                ipAddress = clientIpResolver.resolve(httpRequest)
                lastUsedAt = Instant.now()
            }
        tokenRepository.save(token)
    }

    private fun generatePasswordResetLink(passwordResetCode: String): String {
        return serverHostPublic + "/forgot-password?passwordResetCode=" + URLEncoder.encode(passwordResetCode, Charsets.UTF_8)
    }

    private fun generateEmailChangeLink(token: String): String {
        return serverHostPublic + "/confirm-email-change?token=" + URLEncoder.encode(token, Charsets.UTF_8)
    }

    /**
     * Verified email change — step 1 (authenticated). Validates the requested address (format,
     * not the current one, not already used by another account), mints a short-lived signed
     * token and emails a confirmation link to the NEW address. No DB write — clicking the link
     * is what actually applies the change (see [confirmEmailChange]). Option B: no schema state.
     */
    @Transactional(readOnly = true)
    fun requestEmailChange(newEmailRaw: String) {
        val userId =
            getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
                ?: throw AccessDeniedException("User is not authenticated")
        val dbUser =
            userRepository.findById(userId).getOrNull()
                ?: throw AccessDeniedException("User is not authenticated")

        val newEmail = newEmailRaw.trim()

        if (!org.apache.commons.validator.routines.EmailValidator.getInstance().isValid(newEmail)) {
            throw ParameterValidationException(mapOf("email" to "Invalid email address"))
        }
        if (newEmail.equals(dbUser.email, ignoreCase = true)) {
            throw ParameterValidationException(mapOf("email" to "This is already your email address"))
        }
        if (userRepository.existsByEmailIgnoreCase(newEmail)) {
            throw ParameterValidationException(mapOf("email" to "This email address is already in use"))
        }

        val token = jwtService.generateEmailChangeToken(dbUser.id!!, dbUser.email!!, newEmail)
        val link = generateEmailChangeLink(token)

        // Confirmation goes to the NEW address — clicking the link proves the user owns it.
        val fullName = dbUser.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress = if (fullName != "there") "$fullName <$newEmail>" else newEmail
        val locale = resolveEmailLocale(dbUser.language)
        val subject = messageSource.getMessage("emailChange.subject", null, locale)

        val variables =
            mapOf(
                "fullName" to fullName,
                "emailChangeUrl" to link,
                "newEmail" to newEmail,
                "publicUrl" to serverHostPublic,
                "currentYear" to java.time.LocalDate.now().year.toString(),
            )

        emailService.sendEmail(
            recipients = listOf(recipientAddress),
            subject = subject,
            templateName = "email/emailChange",
            variables = variables,
            locale = locale,
        )
    }

    /**
     * Verified email change — step 2 (public; the signed token IS the authorization). Verifies
     * the token, re-checks the account still holds the original email and the target is still
     * free, applies the new email and revokes all sessions (email is the login identity).
     */
    @Transactional(readOnly = false)
    fun confirmEmailChange(token: String) {
        val claims =
            try {
                jwtService.verifyEmailChangeToken(token)
            } catch (e: io.jsonwebtoken.JwtException) {
                throw ParameterValidationException(mapOf("token" to "This confirmation link is invalid or has expired"))
            }

        val dbUser =
            userRepository.findById(claims.userId).getOrNull()
                ?: throw ParameterValidationException(mapOf("token" to "This confirmation link is invalid or has expired"))

        // The account's email must still be the one the token was minted for, else it already changed.
        if (!dbUser.email.equals(claims.currentEmail, ignoreCase = true)) {
            throw ParameterValidationException(mapOf("token" to "This confirmation link is invalid or has expired"))
        }
        // Re-check the target isn't taken (a race since the link was issued).
        if (userRepository.existsByEmailIgnoreCase(claims.newEmail)) {
            throw ParameterValidationException(mapOf("email" to "This email address is already in use"))
        }

        dbUser.email = claims.newEmail
        // Email is the login identity — force re-login everywhere (mirrors password change).
        tokenService.revokeAllUserTokens(dbUser.id!!)
    }
}
