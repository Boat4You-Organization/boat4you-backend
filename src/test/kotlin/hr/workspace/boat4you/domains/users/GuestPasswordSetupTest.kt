package hr.workspace.boat4you.domains.users

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.controllers.PublicUserController
import hr.workspace.boat4you.domains.users.exceptions.UserRegistrationException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.UserInviteService
import hr.workspace.boat4you.domains.users.services.UserMutationService
import hr.workspace.boat4you.domains.users.services.UserRegistrationService
import hr.workspace.boat4you.security.services.PasswordService
import hr.workspace.boat4you.security.services.UserAuthService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.openapitools.model.UserEmailVerificationRequest
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.Optional

/**
 * Guest account takeover fix (security audit 15.11.2026, HIGH): a reservation id + e-mail no longer sets a guest's
 * password. The account stays closed until the guest sets a password through the invite link e-mailed to them; a
 * repeat guest booking re-sends that link, and never touches an account that already has a password.
 */
class GuestPasswordSetupTest {
    private val userRepository = mock(UserRepository::class.java)
    private val messages = StaticMessageSource().apply { addMessage("userInvite.subject", Locale.ENGLISH, "Welcome aboard") }
    private val inviteService =
        UserInviteService(
            mock(PasswordService::class.java),
            userRepository,
            mock(EmailService::class.java),
            messages,
            Duration.ofDays(7),
            "https://www.boat4you.com",
        )

    private fun guest(
        registration: UserRegistrationStatusEnum,
        invite: UserInviteStatusEnum,
    ): UserEntity {
        val user =
            UserEntity().apply {
                id = 42
                name = "Ana"
                surname = "Guest"
                email = "ana@example.com"
                registrationStatus = registration
                inviteStatus = invite
                inviteCode = "old-code"
            }
        `when`(userRepository.findById(42)).thenReturn(Optional.of(user))
        `when`(userRepository.findByIdIn(listOf(42L))).thenReturn(listOf(user))
        return user
    }

    @Test
    fun `the reservation id + e-mail route is closed with 410 Gone`() {
        PublicUserController(userRepository).setPasswordForReservation().statusCode shouldBe HttpStatus.GONE
    }

    @Test
    fun `a guest without a password gets a fresh invite link`() {
        val user = guest(UserRegistrationStatusEnum.STARTED, UserInviteStatusEnum.INVITED)

        inviteService.reinviteGuestWithoutPassword(42)

        user.inviteStatus shouldBe UserInviteStatusEnum.INVITED
        user.inviteCode shouldNotBe "old-code"
        user.registrationStatus shouldBe UserRegistrationStatusEnum.STARTED
    }

    @Test
    fun `a registered account or an accepted invite is left alone`() {
        val registered = guest(UserRegistrationStatusEnum.REGISTERED, UserInviteStatusEnum.INVITED)
        inviteService.reinviteGuestWithoutPassword(42)
        registered.inviteCode shouldBe "old-code"

        val accepted = guest(UserRegistrationStatusEnum.STARTED, UserInviteStatusEnum.ACCEPTED)
        inviteService.reinviteGuestWithoutPassword(42)
        accepted.inviteCode shouldBe "old-code"
    }

    @Test
    fun `an unknown user id does nothing`() {
        `when`(userRepository.findById(7)).thenReturn(Optional.empty())
        inviteService.reinviteGuestWithoutPassword(7)
    }

    @Test
    fun `a repeat booking re-sends a still valid link instead of breaking it`() {
        val user =
            guest(UserRegistrationStatusEnum.STARTED, UserInviteStatusEnum.INVITED).apply {
                inviteTime = Instant.now().minusSeconds(3600)
            }
        val sentAt = user.inviteTime

        inviteService.reinviteGuestWithoutPassword(42)

        user.inviteCode shouldBe "old-code"
        user.inviteTime shouldBe sentAt
    }

    @Test
    fun `a guest account cannot be opened with the sign-up e-mail code`() {
        val registration =
            UserRegistrationService(
                userRepository,
                mock(UserMutationService::class.java),
                mock(UserAuthService::class.java),
                mock(EmailService::class.java),
                messages,
                "https://www.boat4you.com",
            )
        val user =
            guest(UserRegistrationStatusEnum.STARTED, UserInviteStatusEnum.INVITED).apply {
                emailVerificationCode = "123456"
                verificationCodeIssuedAt = Instant.now().minusSeconds(120)
            }
        `when`(userRepository.findByEmail("ana@example.com")).thenReturn(user)

        assertThrows<UserRegistrationException> {
            registration.verifyEmail(
                UserEmailVerificationRequest("ana@example.com", "123456"),
                mock(HttpServletRequest::class.java),
            )
        }
        // No new code is mailed to a guest either.
        registration.resendEmailVerificationCode("ana@example.com")
        user.emailVerificationCode shouldBe "123456"
        user.registrationStatus shouldBe UserRegistrationStatusEnum.STARTED
    }
}
