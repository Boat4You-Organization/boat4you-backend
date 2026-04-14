package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.exceptions.UserInviteException
import hr.workspace.boat4you.domains.users.exceptions.UserInviteExceptionType
import hr.workspace.boat4you.domains.users.exceptions.UsersDoNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.exceptions.PasswordException
import hr.workspace.boat4you.security.services.PasswordService
import org.bouncycastle.util.encoders.Base64
import org.openapitools.model.SetUserPasswordBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
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
    @Value("\${application.invites.user.expiration}")
    private val inviteDuration: Duration,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
) {
    private val secureRandom = SecureRandom.getInstance("SHA1PRNG")

    @Transactional(readOnly = false)
    fun inviteUsers(userIds: List<Long>) {
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

            val emailVariables =
                mapOf(
                    "messageText" to "Dear ${dbUser.name}, you've been invited to boat4you!",
                    "inviteLink" to serverHostPublic + "/signup?inviteCode=" + URLEncoder.encode(dbUser.inviteCode!!, Charsets.UTF_8),
                    "publicUrl" to serverHostPublic,
                )
            emailService.sendEmail(
                recipients = listOf(dbUser.email),
                subject = "You have been invited to Boat4You",
                templateName = "email/userInvite",
                variables = emailVariables,
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

        if (setUserPasswordBody.password.length < 6) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH)
        }

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
