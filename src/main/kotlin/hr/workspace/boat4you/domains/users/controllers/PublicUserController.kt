package hr.workspace.boat4you.domains.users.controllers

import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

// Public user actions that don't need a Bearer token. Mounted under /public/**
// so Security allow-lists them automatically. Everything here must defend
// against anonymous abuse on its own (rate limit / captcha at the gateway).
@Tag(name = "Users (public)")
@Validated
@Controller
@RequestMapping("/public/users")
class PublicUserController(
    private val userRepository: UserRepository,
) {
    // Guest password setup by reservation id + e-mail is CLOSED (15.11.2026, security audit API-1, HIGH).
    // The pair proved nothing: anyone can make a guest booking under someone else's e-mail (the booking then
    // attaches to that person's account), so an attacker could set the password of an existing guest account
    // (their reservations, crew lists with passport data, invoices, GDPR export) or pre-claim an unused e-mail.
    // A guest now sets the password only through the invite link e-mailed to them (UserInviteService:
    // /signup?inviteCode=…), which proves they own the address; a repeat guest booking re-sends that link
    // (ReservationFlowMutationService). The route stays as 410 Gone so an old front end gets a clear answer.
    @Operation(summary = "Closed: guests set their password through the e-mailed invite link")
    @PostMapping("/set-password-for-reservation")
    fun setPasswordForReservation(): ResponseEntity<Unit> = ResponseEntity.status(HttpStatus.GONE).build()

    // One-click email unsubscribe (audit B2). The token is the proof of intent;
    // we set marketing_opt_out=true for the matching user so the birthday cron
    // skips them. Enumeration-safe + idempotent: always returns 200, and is a
    // silent no-op for an unknown/garbage token so it can't be probed.
    @Operation(summary = "Unsubscribe from courtesy/marketing emails via a one-click token")
    @PostMapping("/unsubscribe/{token}")
    @Transactional
    fun unsubscribe(
        @PathVariable token: String,
    ): ResponseEntity<Unit> {
        userRepository.findByUnsubscribeToken(token)?.let { user ->
            if (!user.marketingOptOut) {
                user.marketingOptOut = true
                userRepository.save(user)
            }
        }
        return ResponseEntity.ok().build()
    }
}
