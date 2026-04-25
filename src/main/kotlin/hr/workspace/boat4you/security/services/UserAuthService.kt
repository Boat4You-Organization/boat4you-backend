package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.models.WebRequestContext
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
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.security.SecureRandom
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
    private val emailService: EmailService,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
    @Value("\${application.invites.user.allow-uninvited-login}")
    private val allowUninvitedLogin: Boolean,
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
            dbUser.lastUnsuccessfulLogin!!.plusSeconds(MAX_LOGIN_ATTEMPTS_LOCK_PERIOD).isAfter(Instant.now())
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

        val userDomainEntity = dbUser.toDomainUser()
        val userContext = requestContextFactory.buildRequestContext(userDomainEntity)
        httpRequest.setAttribute(WebRequestContext.CONTEXT_ATTRIBUTE, userContext)

        val jwtToken = jwtService.generateToken(userDomainEntity)
        val refreshToken = jwtService.generateRefreshToken(userDomainEntity)

        saveUserToken(dbUser, jwtToken.first, jwtToken.second)
        saveUserToken(dbUser, refreshToken.first, refreshToken.second)

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

        saveUserToken(dbUser, jwtToken.first, jwtToken.second)
        saveUserToken(dbUser, refreshToken.first, refreshToken.second)

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
            storedToken.isExpired = true
            storedToken.isRevoked = true

            tokenRepository.save(storedToken)
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

        if (body.newPassword.length < 6) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH)
        }

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
            saveUserToken(dbUser, accessToken.first, accessToken.second)

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

        if (dbUser == null) {
            throw UserDoesNotExistException()
        }

        dbUser.passwordResetCode = Base64.toBase64String(generateRandomBytes())
        val passwordResetLink = generatePasswordResetLink(dbUser.passwordResetCode!!)

        val variables =
            mapOf(
                "message" to "Dear ${dbUser.getFullName()}, please reset your password.",
                "passwordResetUrl" to passwordResetLink,
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(dbUser.email),
            subject = "Reset your Boat4you password",
            templateName = "email/passwordReset",
            variables = variables,
        )
    }

    private fun generateRandomBytes(length: Int = 64): ByteArray {
        val randomBytes = ByteArray(length)
        secureRandom.nextBytes(randomBytes)
        return randomBytes
    }

    fun checkPasswordResetValidity(passwordResetCode: String): UserEntity {
        return userRepository.findByPasswordResetCode(passwordResetCode) ?: throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_RESET_INVALID)
    }

    @Transactional(readOnly = false)
    fun resetPassword(
        passwordResetCode: String,
        userPasswordBody: SetUserPasswordBody,
    ) {
        val dbUser = checkPasswordResetValidity(passwordResetCode)

        if (userPasswordBody.password.length < 6) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH)
        }

        dbUser.apply {
            password = passwordService.encodePassword(userPasswordBody.password)
            this.passwordResetCode = null
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
    ) {
        val token =
            TokenEntity().apply {
                value = jwtToken
                user = dbUser
                this.expiresAt = expiresAt.toInstant()
            }
        tokenRepository.save(token)
    }

    private fun generatePasswordResetLink(passwordResetCode: String): String {
        return serverHostPublic + "/forgot-password?passwordResetCode=" + URLEncoder.encode(passwordResetCode, Charsets.UTF_8)
    }
}
