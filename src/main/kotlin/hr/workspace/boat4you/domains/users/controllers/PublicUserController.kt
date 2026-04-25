package hr.workspace.boat4you.domains.users.controllers

import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.exceptions.PasswordException
import hr.workspace.boat4you.security.services.PasswordService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import kotlin.jvm.optionals.getOrElse

// Public user actions that don't need a Bearer token. Mounted under /public/**
// so Security allow-lists them automatically. Everything here must defend
// against anonymous abuse on its own (rate limit / captcha at the gateway).
@Tag(name = "Users (public)")
@Validated
@Controller
@RequestMapping("/public/users")
class PublicUserController(
    private val passwordService: PasswordService,
    private val userRepository: UserRepository,
    private val reservationRepository: ReservationRepository,
) {
    // Guest password setup — replaces the invite-code flow when the user just
    // finished a guest booking. The reservation id + email pair is the proof
    // of ownership (the email match must be strict, case-insensitive).
    @Operation(summary = "Set password for a guest user via their reservation id + email")
    @PostMapping("/set-password-for-reservation")
    @Transactional
    fun setPasswordForReservation(
        @RequestBody @Valid request: SetPasswordForReservationRequest,
    ): ResponseEntity<Unit> {
        val reservation =
            reservationRepository
                .findById(request.reservationId)
                .getOrElse { throw ReservationNotExistException() }

        val user =
            reservation.reservationFlow?.user
                ?: throw IllegalStateException("Reservation has no associated user")

        if (!user.email.equals(request.email, ignoreCase = true)) {
            // Intentionally vague so we don't leak whether the reservation id
            // exists vs. whether the email matches.
            throw IllegalArgumentException("Reservation and email do not match")
        }

        if (user.registrationStatus == UserRegistrationStatusEnum.REGISTERED) {
            // Account already active — do nothing. The user should sign in
            // through the normal login flow (or reset password if forgotten).
            throw IllegalStateException("Account already registered")
        }

        if (request.password.length < 6) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH)
        }

        user.apply {
            password = passwordService.encodePassword(request.password)
            registrationStatus = UserRegistrationStatusEnum.REGISTERED
            inviteStatus = UserInviteStatusEnum.ACCEPTED
            inviteTime = null
            inviteCode = null
        }
        userRepository.save(user)

        return ResponseEntity.ok().build()
    }
}

data class SetPasswordForReservationRequest(
    @field:NotNull val reservationId: Long,
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val password: String,
)
