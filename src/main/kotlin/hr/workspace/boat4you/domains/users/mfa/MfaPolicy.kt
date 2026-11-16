package hr.workspace.boat4you.domains.users.mfa

import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.services.toDomainUser
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Who signs in in two steps (Mario 16.11.2026):
 * - phase 1 (MFA_REQUIRED_FOR_ADMIN=false, the default): every account that set up a passkey or an authenticator app;
 * - phase 2 (MFA_REQUIRED_FOR_ADMIN=true, once every admin has set theirs up): also every SYSTEM_ADMIN / MANAGER. One
 *   who still has nothing set up is refused (review 16.11.2026: the password alone must not open an admin).
 */
@Service
class MfaPolicy(
    private val passkeys: UserPasskeyRepository,
    private val totps: UserTotpRepository,
    private val recoveryCodes: UserRecoveryCodeRepository,
    private val trustedDevices: UserTrustedDeviceRepository,
    @Value("\${application.mfa.required-for-admin:false}")
    val requiredForAdmin: Boolean,
) {
    fun isAdmin(user: UserEntity): Boolean = user.toDomainUser().authorities.any { it.authority in ADMIN_ROLES }

    fun hasPasskey(userId: Long): Boolean = passkeys.findAllByUserId(userId).isNotEmpty()

    fun hasTotp(userId: Long): Boolean = totps.findById(userId).orElse(null)?.secret != null

    /** A passkey or an authenticator app is set up (recovery codes alone are only the way back in). */
    fun isEnrolled(userId: Long): Boolean = hasPasskey(userId) || hasTotp(userId)

    fun isRequired(user: UserEntity): Boolean {
        val id = user.id ?: return false
        return isEnrolled(id) || (requiredForAdmin && isAdmin(user))
    }

    /** Password reset (review 16.11.2026): remembered browsers ask for the second step again. */
    @Transactional
    fun forgetTrustedDevices(userId: Long) {
        trustedDevices.deleteAllByUserId(userId)
    }

    /** GDPR delete (UserMutationService.deleteUser): the account's second-step data goes with it. */
    @Transactional
    fun forgetUser(userId: Long) {
        passkeys.deleteAllByUserId(userId)
        totps.deleteById(userId)
        recoveryCodes.deleteAllByUserId(userId)
        trustedDevices.deleteAllByUserId(userId)
    }

    companion object {
        val ADMIN_ROLES = setOf("SYSTEM_ADMIN", "MANAGER")
    }
}
