package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.exceptions.UserInviteException
import hr.workspace.boat4you.domains.users.exceptions.UserInviteExceptionType
import hr.workspace.boat4you.domains.users.exceptions.UsersDoNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.services.PasswordPolicy
import hr.workspace.boat4you.security.services.PasswordService
import org.bouncycastle.util.encoders.Base64
import org.openapitools.model.SetUserPasswordBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.apply
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.isNotEmpty
import kotlin.collections.map
import kotlin.collections.minus

@Service
class UserInviteService(
    private val passwordService: PasswordService,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    @Value("\${application.invites.user.expiration}")
    private val inviteDuration: Duration,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
) {
    private val secureRandom = SecureRandom.getInstance("SHA1PRNG")

    /**
     * Send invite emails to a batch of users.
     *
     * Locale handling:
     *   * `forceEnglish = false` (booking-driven flow) → email is rendered
     *     in the recipient's `user.language` (captured at first contact).
     *     Falls back to English if not set.
     *   * `forceEnglish = true` (admin "Invite user" button in the back
     *     office) → email is always English regardless of recipient
     *     language. Mario's rule (3.5.2026): admin-triggered invites are
     *     internal/team operational comms and don't depend on guest UX.
     */
    @Transactional(readOnly = false)
    fun inviteUsers(userIds: List<Long>, forceEnglish: Boolean = false) {
        val dbUsers = userRepository.findByIdIn(userIds)
        val missingUserIds = userIds - dbUsers.map { it.id!! }
        if (missingUserIds.isNotEmpty()) {
            throw UsersDoNotExistException(missingUserIds)
        }

        val inviteAcceptedUserIds = dbUsers.filter { it.inviteStatus == UserInviteStatusEnum.ACCEPTED }.map { it.id!! }
        if (inviteAcceptedUserIds.isNotEmpty()) {
            throw UserInviteException(UserInviteExceptionType.INVITE_ALREADY_ACCEPTED, inviteAcceptedUserIds)
        }

        dbUsers.forEach { dbUser ->
            dbUser.inviteCode = Base64.toBase64String(generateRandomBytes())
            dbUser.inviteTime = Instant.now()
            dbUser.inviteStatus = UserInviteStatusEnum.INVITED

            // Template uses fullName ("Welcome aboard, {fullName}").
            // Falls back to "there" only when both name + surname are blank
            // (rare — admin-invited stubs occasionally lack one or both).
            val fullName =
                dbUser.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"

            // Inbox-friendly recipient: render as `Mario Kuzmanic <email>` so
            // the customer's mail client shows their name on the To: row,
            // not a bare address. MimeMessageHelper.setTo() forwards the
            // string through InternetAddress.parse(), which handles RFC2822
            // formatted addresses. Falls back to bare email if name blank.
            val recipientAddress =
                if (fullName != "there") "$fullName <${dbUser.email}>" else dbUser.email

            val locale = resolveEmailLocale(dbUser.language, forceEnglish)
            val subject = messageSource.getMessage("userInvite.subject", null, locale)

            val emailVariables =
                mapOf(
                    "fullName" to fullName,
                    "inviteLink" to serverHostPublic + "/signup?inviteCode=" + URLEncoder.encode(dbUser.inviteCode!!, Charsets.UTF_8),
                    "publicUrl" to serverHostPublic,
                    "currentYear" to LocalDate.now().year.toString(),
                )
            emailService.sendEmail(
                recipients = listOf(recipientAddress),
                subject = subject,
                templateName = "email/userInvite",
                variables = emailVariables,
                locale = locale,
            )
        }
    }

    fun checkInvitationValidity(inviteCode: String): UserEntity {
        val dbUser = userRepository.findByInviteCode(inviteCode) ?: throw UserInviteException(UserInviteExceptionType.INVALID_INVITE_CODE)

        if (dbUser.inviteTime?.plus(inviteDuration)?.isBefore(Instant.now()) == true) {
            throw UserInviteException(UserInviteExceptionType.INVITE_EXPIRED)
        }

        if (dbUser.inviteStatus == UserInviteStatusEnum.ACCEPTED) {
            throw UserInviteException(UserInviteExceptionType.INVITE_ALREADY_ACCEPTED)
        }

        return dbUser
    }

    @Transactional(readOnly = false)
    fun acceptInvitation(
        inviteCode: String,
        setUserPasswordBody: SetUserPasswordBody,
    ) {
        val dbUser = checkInvitationValidity(inviteCode)

        PasswordPolicy.validate(setUserPasswordBody.password)

        dbUser.apply {
            password = passwordService.encodePassword(setUserPasswordBody.password)
            inviteStatus = UserInviteStatusEnum.ACCEPTED
            registrationStatus = UserRegistrationStatusEnum.REGISTERED
            inviteTime = null
            this.inviteCode = null
        }

        // Roles have already been set when User entity was created, before sending the invite.
    }

    private fun generateRandomBytes(length: Int = 64): ByteArray {
        val randomBytes = ByteArray(length)
        secureRandom.nextBytes(randomBytes)
        return randomBytes
    }
}
