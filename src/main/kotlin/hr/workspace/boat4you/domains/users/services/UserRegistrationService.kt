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
    fun registerUser(input: UserRegistrationRequest) {
        PasswordPolicy.validate(input.password)

        // Enumeration-safe registration (audit A1 / OWASP A07): the endpoint
        // returns the SAME 204 whether or not the email already has an account,
        // so it can't be used as an account-existence oracle. A brand-new email
        // creates a STARTED account + verification code; an already-registered
        // email gets a "you already have an account" email instead. The
        // throwing 1201 path stays on the admin-only `POST /users` create flow
        // (UserController), where enumeration by an authenticated admin is not
        // a threat.
        //
        // Existence is checked up-front (NOT by catching createUser's
        // UserAlreadyExistsException) on purpose: createUser is its own
        // @Transactional, so catching its exception here would leave the shared
        // transaction marked rollback-only and fail at commit. The only
        // residual case is two brand-new identical emails racing, where the
        // loser gets a 400 — not an enumeration vector. existsByEmailIgnoreCase
        // mirrors the exact check createUser performs internally.
        if (userRepository.existsByEmailIgnoreCase(input.email)) {
            sendAlreadyRegisteredEmail(input.email)
            return
        }

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
    }

    /**
     * Public, anonymous resend (AuthController `POST /auth/register/resendVerificationCode`).
     * Enumeration-safe + anti-spam: resolve by email and silently no-op for
     * unknown / already-registered addresses, and silently skip when a code was
     * issued less than 60s ago. The caller always gets the same 200, so this
     * can't be used to probe which emails have a pending registration, nor to
     * flood a victim with verification mail.
     */
    @Transactional(readOnly = false)
    fun resendEmailVerificationCode(email: String) {
        val dbUser =
            userRepository.findByEmail(email)
                ?.takeIf { it.registrationStatus == UserRegistrationStatusEnum.STARTED }
                ?: return

        if (dbUser.verificationCodeIssuedAt?.plusSeconds(60)?.isAfter(Instant.now()) == true) {
            return
        }

        reissueVerificationCode(dbUser)
    }

    /**
     * Authenticated self-service resend (UserController `POST /users/me/resend-verification`).
     * The caller is identified by their JWT, so there's no enumeration concern —
     * keep the informative errors (throws if already verified, or within the 60s
     * rate-limit window) for a better signed-in UX.
     */
    @Transactional(readOnly = false)
    fun resendEmailVerificationCode(userId: Long) {
        val dbUser = userRepository.findById(userId).getOrElse { throw UserDoesNotExistException() }
        if (dbUser.registrationStatus != UserRegistrationStatusEnum.STARTED) {
            throw UserRegistrationException(UserRegistrationExceptionReason.USER_ALREADY_REGISTERED)
        }
        if (dbUser.verificationCodeIssuedAt!!.plusSeconds(60).isAfter(Instant.now())) {
            throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_CODE_REQUESTED_TOO_SOON)
        }

        reissueVerificationCode(dbUser)
    }

    private fun reissueVerificationCode(dbUser: UserEntity) {
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
        // Resolve by email (was userId) so the endpoint can't be used to probe
        // which user IDs exist. A missing or already-registered account yields
        // the SAME generic "code does not match" failure as a wrong code, so
        // verification leaks neither account existence nor registration state.
        val dbUser =
            userRepository.findByEmail(input.email)
                ?.takeIf { it.registrationStatus == UserRegistrationStatusEnum.STARTED }
                ?: throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_CODE_DOES_NOT_MATCH)

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

    /**
     * Render + dispatch the "you already have an account" email — the
     * enumeration-safe counterpart to the verification email. Sent by
     * `registerUser` when someone tries to sign up with an address that is
     * already registered. Only the real inbox owner ever receives it, so the
     * copy can be explicit ("you already have an account — just log in"); the
     * party that triggered the attempt gets the same 204 either way and learns
     * nothing about whether the account exists.
     */
    private fun sendAlreadyRegisteredEmail(email: String) {
        val existing = userRepository.findByEmail(email)
        val displayName =
            existing?.getFullName()?.trim()?.takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (displayName != "there") "$displayName <$email>" else email

        val customerLocale: Locale = resolveEmailLocale(existing?.language)
        val subject = messageSource.getMessage("accountExists.subject", null, customerLocale)

        val variables: Map<String, Any?> =
            mapOf(
                "fullName" to displayName,
                "loginUrl" to serverHostPublic,
                "receivedAt" to formatReceivedAt(),
                "currentYear" to LocalDate.now().year.toString(),
            )

        emailService.sendEmail(
            recipients = listOf(recipientAddress),
            subject = subject,
            templateName = "email/accountAlreadyExists",
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
