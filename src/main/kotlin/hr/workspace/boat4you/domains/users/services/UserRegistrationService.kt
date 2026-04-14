package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.services.getRandomNumericalString
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.exceptions.UserRegistrationException
import hr.workspace.boat4you.domains.users.exceptions.UserRegistrationException.UserRegistrationExceptionReason
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.services.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.openapitools.model.TokenResponse
import org.openapitools.model.User
import org.openapitools.model.UserEmailVerificationRequest
import org.openapitools.model.UserRegistrationRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

@Service
class UserRegistrationService(
    private val userRepository: UserRepository,
    private val userMutationService: UserMutationService,
    private val userAuthService: UserAuthService,
    private val emailService: EmailService,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
) {
    @Transactional(readOnly = false)
    fun registerUser(input: UserRegistrationRequest): User {
        val userModel = userMutationService.createUser(input.toUserModel())
        val dbUser = userRepository.findById(userModel.id!!).get()
        dbUser.apply {
            emailVerificationCode = getRandomNumericalString(6)
            registrationStatus = UserRegistrationStatusEnum.STARTED
            verificationCodeIssuedAt = Instant.now()
            inviteStatus = UserInviteStatusEnum.ACCEPTED
        }

        userRepository.save(dbUser)

        val emailVariables =
            mapOf(
                "message" to "Dear ${input.name}, please verify your email address using the code below to finish setting up your account.",
                "digit1" to dbUser.emailVerificationCode!![0],
                "digit2" to dbUser.emailVerificationCode!![1],
                "digit3" to dbUser.emailVerificationCode!![2],
                "digit4" to dbUser.emailVerificationCode!![3],
                "digit5" to dbUser.emailVerificationCode!![4],
                "digit6" to dbUser.emailVerificationCode!![5],
                "publicUrl" to serverHostPublic,
            )
        emailService.sendEmail(
            recipients = listOf(dbUser.email),
            subject = "Verify Your Boat4You Email",
            templateName = "email/emailVerification",
            variables = emailVariables,
        )

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
        }

        val emailVariables =
            mapOf(
                "message" to "Dear ${dbUser.getFullName()}, please verify your email address using the code below to finish setting up your account.",
                "digit1" to dbUser.emailVerificationCode!![0],
                "digit2" to dbUser.emailVerificationCode!![1],
                "digit3" to dbUser.emailVerificationCode!![2],
                "digit4" to dbUser.emailVerificationCode!![3],
                "digit5" to dbUser.emailVerificationCode!![4],
                "digit6" to dbUser.emailVerificationCode!![5],
                "publicUrl" to serverHostPublic,
            )
        emailService.sendEmail(
            recipients = listOf(dbUser.email),
            subject = "Verify Your Boat4You Email",
            templateName = "email/emailVerification",
            variables = emailVariables,
        )
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
        if (dbUser.emailVerificationCode != input.verificationCode) {
            throw UserRegistrationException(UserRegistrationExceptionReason.VERIFICATION_CODE_DOES_NOT_MATCH)
        }

        dbUser.apply {
            emailVerificationCode = null
            registrationStatus = UserRegistrationStatusEnum.REGISTERED
            verificationCodeIssuedAt = null
        }

        return userAuthService.issueTokenAtRegistration(dbUser, httpRequest)
    }
}
