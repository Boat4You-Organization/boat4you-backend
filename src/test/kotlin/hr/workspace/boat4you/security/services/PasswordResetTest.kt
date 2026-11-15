package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.RequestContextFactory
import hr.workspace.boat4you.security.jpa.TokenRepository
import hr.workspace.boat4you.security.jpa.TokenService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.openapitools.model.RequestPasswordResetBody
import org.openapitools.model.SetUserPasswordBody
import org.springframework.context.support.StaticMessageSource
import java.time.Duration

/**
 * Forgot password must work for everyone (Mario 15.11.2026): a registered customer and a guest from a booking whose
 * account has no password yet, also when the address is typed in a different letter case than it was booked with.
 */
class PasswordResetTest {
    private val users = mock(UserRepository::class.java)
    private val passwords = mock(PasswordService::class.java)
    private val auth =
        UserAuthService(
            users,
            mock(TokenRepository::class.java),
            mock(JwtService::class.java),
            passwords,
            mock(TokenService::class.java),
            mock(RequestContextFactory::class.java),
            mock(ClientIpResolver::class.java),
            mock(EmailService::class.java),
            StaticMessageSource().apply { setUseCodeAsDefaultMessage(true) },
            "https://www.boat4you.com",
            false,
            Duration.ofMinutes(60),
        )

    private fun account(
        email: String,
        registration: UserRegistrationStatusEnum,
    ) = UserEntity().apply {
        id = 42
        name = "Ana"
        surname = "Horvat"
        this.email = email
        registrationStatus = registration
        inviteStatus = UserInviteStatusEnum.INVITED
        inviteCode = "invite"
    }

    private fun requestReset(typed: String) = auth.requestPasswordReset(RequestPasswordResetBody(email = typed), mock(HttpServletRequest::class.java))

    @Test
    fun `a guest from a booking resets the password, also typed in another case, and the account opens`() {
        val guest = account("Ana.Horvat@Gmail.com", UserRegistrationStatusEnum.STARTED)
        `when`(users.findAllActiveByEmailIgnoreCase("ana.horvat@gmail.com")).thenReturn(listOf(guest))

        requestReset("ana.horvat@gmail.com")
        val code = guest.passwordResetCode
        code shouldNotBe null

        `when`(users.findByPasswordResetCode(code!!)).thenReturn(guest)
        `when`(passwords.encodePassword("Sail-Croatia-2026")).thenReturn("hashed")
        auth.resetPassword(code, SetUserPasswordBody("Sail-Croatia-2026"))

        guest.password shouldBe "hashed"
        guest.registrationStatus shouldBe UserRegistrationStatusEnum.REGISTERED
        guest.inviteStatus shouldBe UserInviteStatusEnum.ACCEPTED
        guest.passwordResetCode shouldBe null
        guest.inviteCode shouldBe null
    }

    @Test
    fun `a registered customer resets the password with the exact address`() {
        val customer = account("marko@example.com", UserRegistrationStatusEnum.REGISTERED)
        `when`(users.findByEmail("marko@example.com")).thenReturn(customer)

        requestReset("marko@example.com")

        customer.passwordResetCode shouldNotBe null
    }

    @Test
    fun `two accounts that differ only in case are not guessed between`() {
        val first = account("Ivo@example.com", UserRegistrationStatusEnum.REGISTERED)
        val second = account("ivo@Example.com", UserRegistrationStatusEnum.REGISTERED)
        `when`(users.findAllActiveByEmailIgnoreCase("IVO@EXAMPLE.COM")).thenReturn(listOf(first, second))

        requestReset("IVO@EXAMPLE.COM")

        first.passwordResetCode shouldBe null
        second.passwordResetCode shouldBe null
    }
}
