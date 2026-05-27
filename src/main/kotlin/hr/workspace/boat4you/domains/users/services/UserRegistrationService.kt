package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.services.getRandomNumericalString
import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.exceptions.UserRegistrationException
import hr.workspace.boat4you.domains.users.exceptions.UserRegistrationException.UserRegistrationExceptionReason
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.services.PasswordPolicy
import hr.workspace.boat4you.security.services.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.openapitools.model.TokenResponse
import org.openapitools.model.User
import org.openapitools.model.UserEmailVerificationRequest
import org.openapitools.model.UserRegistrationRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.jvm.optionals.getOrElse

@Service
class UserRegistrationService(
    private val userRepository: UserRepository,
    private val userMutationService: UserMutationService,
    private val userAuthService: UserAuthService,
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
) {
    companion object {
        const val MAX_VERIFICATION_ATTEMPTS = 5
        const val VERIFICATION_CODE_TTL_SECONDS = 1800L // 30 minutes
    }
    // Header "Sent {date·time (GMT±X)}" — same Europe/Zagreb wall-clock
    // pattern used by the password-reset / option-reminder emails so all
    // customer-facing transactional mail carries a consistent timestamp.
    private val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")

    @Transactional(readOnly = false)
    fun registerUser(input: UserRegistrationRequest): User {
        PasswordPolicy.validate(input.password)
        val userModel = userMutationService.createUser(input.toUserModel())
        val dbUser = userRepository.findById(userModel.id!!).get()
        dbUser.apply {
            emailVerificationCode = getRandomNumericalString(6)
            registrationStatus = UserRegistrationStatusEnum.STARTED
            verificationCodeIssuedAt = Instant.now()
            inviteStatus = UserInviteStatusEnum.ACCEPTED
        }

        userRepository.save(dbUser)

        sendVerificationEmail(dbUser)

        return dbUser.toUserModel()
    }

    @Transactional(readOnly = false)
    fun resendEmailVerificationCode(userId: Long) {
        val dbUser = userRepository.findById(userId).getOrElse { throw UserDoesNotExistException() }
        if (dbUser.registrationStatus != UserRegistrationStatusEnum.STARTED) {
            throw UserRegistrationException(UserRegistrationExceptionReason.USER_ALREADY_REGISTERED)
        }
        if (dbUser.verificationCodeIssuedAt!!.plusSeconds(60).isAfter(Instant.now())) {
            throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_CODE_REQUESTED_TOO_SOON)
        }

        dbUser.apply {
            emailVerificationCode = getRandomNumericalString(6)
            verificationCodeIssuedAt = Instant.now()
            verificationAttempts = 0
        }

        sendVerificationEmail(dbUser)
    }

    @Transactional(readOnly = false)
    fun verifyEmail(
        input: UserEmailVerificationRequest,
        httpRequest: HttpServletRequest,
    ): TokenResponse {
        val dbUser = userRepository.findById(input.userId).getOrElse { throw UserDoesNotExistException() }
        if (dbUser.registrationStatus != UserRegistrationStatusEnum.STARTED) {
            throw UserRegistrationException(UserRegistrationExceptionReason.USER_ALREADY_REGISTERED)
        }
        if (dbUser.verificationAttempts >= MAX_VERIFICATION_ATTEMPTS) {
            throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_ATTEMPTS_EXCEEDED)
        }
        if (dbUser.verificationCodeIssuedAt != null &&
            dbUser.verificationCodeIssuedAt!!.plusSeconds(VERIFICATION_CODE_TTL_SECONDS).isBefore(Instant.now())
        ) {
            throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_CODE_EXPIRED)
        }
        if (dbUser.emailVerificationCode != input.verificationCode) {
            dbUser.verificationAttempts++
            userRepository.save(dbUser)
            throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_CODE_DOES_NOT_MATCH)
        }

        dbUser.apply {
            emailVerificationCode = null
            registrationStatus = UserRegistrationStatusEnum.REGISTERED
            verificationCodeIssuedAt = null
            verificationAttempts = 0
        }

        return userAuthService.issueTokenAtRegistration(dbUser, httpRequest)
    }

    /**
     * Render + dispatch the 6-digit-code verification email. Shared by
     * `registerUser` (first send during signup) and
     * `resendEmailVerificationCode` (manual resend after the 60s gate),
     * so both paths produce an identical, fully-i18n'd message:
     *  - subject resolved from `emailVerification.subject` per recipient locale
     *  - RFC2822 `To: Mario Kuzmanic <…>` when we have a name (Mario rule
     *    3.5.2026: client-facing emails always carry full name)
     *  - 24h validity disclaimer + body copy fully localized via the
     *    `emailVerification.*` bundle keys
     */
    private fun sendVerificationEmail(dbUser: UserEntity) {
        // Inbox-friendly recipient: render as `Mario Kuzmanic <email>` when
        // we have a name; bare email otherwise. Mario rule (3.5.2026): all
        // client-facing emails carry full name in the To: header.
        val displayName =
            dbUser.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (displayName != "there") "$displayName <${dbUser.email}>" else dbUser.email

        // Localize per recipient — user.language captured at signup from
        // the front-end's negotiated locale. Falls back to English when null.
        val customerLocale: Locale = resolveEmailLocale(dbUser.language)
        val subject = messageSource.getMessage("emailVerification.subject", null, customerLocale)

        val code = dbUser.emailVerificationCode!!
        val variables: Map<String, Any?> =
            mapOf(
                "fullName" to displayName,
                "digit1" to code[0],
                "digit2" to code[1],
                "digit3" to code[2],
                "digit4" to code[3],
                "digit5" to code[4],
                "digit6" to code[5],
                "publicUrl" to serverHostPublic,
                "receivedAt" to formatReceivedAt(),
                "currentYear" to LocalDate.now().year.toString(),
            )

        emailService.sendEmail(
            recipients = listOf(recipientAddress),
            subject = subject,
            templateName = "email/emailVerification",
            variables = variables,
            locale = customerLocale,
        )
    }

    /** Format `Apr 24, 2026 · 11:12 (GMT+2)` in Europe/Zagreb. Mirrors the
     *  helper in `OptionExpiryService` / `PaymentPendingNotificationService`
     *  so every customer-facing email header reads consistently. */
    private fun formatReceivedAt(): String {
        val nowZoned = ZonedDateTime.now(ZoneId.of("Europe/Zagreb"))
        val offsetHours = nowZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        return "${nowZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"
    }
}
