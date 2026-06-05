package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.UserMutationService
import hr.workspace.boat4you.security.exceptions.InternalLoginException
import jakarta.servlet.http.HttpServletRequest
import org.openapitools.model.RoleEnum
import org.openapitools.model.TokenResponse
import org.openapitools.model.User
import org.openapitools.model.UserRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Social login orchestration. Verifies the provider token, then find-or-creates
 * a local account and issues OUR JWT (same token pair as a password login), so
 * social login layers cleanly on top of the existing auth stack instead of
 * replacing it.
 *
 * Auto-linking policy (Mario decision): a Google-verified email is attached to
 * an existing local account that owns the same address. This is gated on the
 * email being **verified by Google** — without that gate, anyone able to mint a
 * token for an unverified address could take over a boat4you account.
 */
@Service
class OAuthService(
    private val googleTokenVerifier: GoogleTokenVerifier,
    private val userRepository: UserRepository,
    private val userMutationService: UserMutationService,
    private val userAuthService: UserAuthService,
) {
    private val secureRandom = SecureRandom.getInstance("SHA1PRNG")

    @Transactional(readOnly = false)
    fun loginWithGoogle(
        idToken: String,
        httpRequest: HttpServletRequest,
    ): TokenResponse {
        val identity = googleTokenVerifier.verify(idToken)

        // Linchpin of the whole flow: only a Google-verified email may be
        // auto-linked / used to create an account. An unverified address is
        // rejected as a generic auth failure.
        if (!identity.emailVerified) {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, identity.email)
        }

        val existing = userRepository.findByEmailIgnoreCaseWithRoles(identity.email)
        val user =
            if (existing != null) {
                linkExistingAccount(existing, identity)
            } else {
                createGoogleAccount(identity)
            }

        return userAuthService.issueTokenAtRegistration(user, httpRequest)
    }

    /**
     * Attach the Google identity to an existing local account that owns the same
     * (verified) email. Also finalises a stalled signup: a Google-verified email
     * is proof of inbox ownership, so a STARTED account becomes REGISTERED.
     */
    private fun linkExistingAccount(
        user: UserEntity,
        identity: GoogleIdentity,
    ): UserEntity {
        // A soft-deleted (GDPR-erased) account must not be silently revived via a
        // social login — refuse as a generic auth failure.
        if (user.deletedAt != null) {
            throw InternalLoginException(InternalLoginException.Type.BAD_CREDENTIALS, identity.email)
        }

        user.provider = PROVIDER_GOOGLE
        user.providerId = identity.subject
        if (user.registrationStatus != UserRegistrationStatusEnum.REGISTERED) {
            user.registrationStatus = UserRegistrationStatusEnum.REGISTERED
            user.emailVerificationCode = null
            user.verificationCodeIssuedAt = null
        }
        // Social signups are self-service — accept the invite gate so login
        // checks (allow-uninvited-login=false envs) don't block them.
        user.inviteStatus = UserInviteStatusEnum.ACCEPTED
        user.loginAttempts = 0
        user.lastUnsuccessfulLogin = null
        user.lastLoginAt = Instant.now()
        return userRepository.save(user)
    }

    /**
     * Create a brand-new account for a first-time Google user. The email is
     * already verified by Google, so the account is REGISTERED immediately. A
     * random unguessable password is set (createUser BCrypt-encodes it); the
     * user can run "forgot password" later to also enable email login.
     */
    private fun createGoogleAccount(identity: GoogleIdentity): UserEntity {
        val (firstName, lastName) = resolveName(identity)

        val model =
            User(
                name = firstName,
                surname = lastName,
                email = identity.email,
                phoneNumber = null,
                password = randomPassword(),
                roles = listOf(UserRole(RoleEnum.USER)),
            )
        val created = userMutationService.createUser(model)

        val dbUser = userRepository.findById(created.id!!).get()
        dbUser.apply {
            provider = PROVIDER_GOOGLE
            providerId = identity.subject
            registrationStatus = UserRegistrationStatusEnum.REGISTERED
            inviteStatus = UserInviteStatusEnum.ACCEPTED
            emailVerificationCode = null
            verificationCodeIssuedAt = null
            lastLoginAt = Instant.now()
        }
        return userRepository.save(dbUser)
    }

    /**
     * Derive a (name, surname) pair that satisfies the same validation as a
     * manual signup (each >= 2 chars). Google usually supplies given_name +
     * family_name; we fall back to splitting the display name, then to the email
     * local-part, so a sparse Google profile still yields a usable account the
     * user can refine on /my-profile.
     */
    private fun resolveName(identity: GoogleIdentity): Pair<String, String> {
        val localPart = identity.email.substringBefore('@')
        var first = identity.givenName?.trim().orEmpty()
        var last = identity.familyName?.trim().orEmpty()

        if (first.isBlank() || last.isBlank()) {
            val parts = identity.name?.trim().orEmpty().split(" ").filter { it.isNotBlank() }
            if (first.isBlank()) first = parts.firstOrNull().orEmpty()
            if (last.isBlank()) last = parts.drop(1).joinToString(" ")
        }
        if (first.length < 2) first = localPart
        if (last.length < 2) last = first
        // Final guard so createUser's validateUserInput (>= 2 chars) never rejects.
        if (first.length < 2) first = FALLBACK_NAME
        if (last.length < 2) last = FALLBACK_NAME
        return first to last
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val PROVIDER_GOOGLE = "GOOGLE"
        private const val FALLBACK_NAME = "User"
    }
}
