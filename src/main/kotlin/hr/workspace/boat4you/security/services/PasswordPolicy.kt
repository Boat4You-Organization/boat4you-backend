package hr.workspace.boat4you.security.services

import hr.workspace.boat4you.security.exceptions.PasswordException

/**
 * Centralised password-acceptance check.
 *
 * F1-004 fix. The repo previously had four near-identical `if
 * (password.length < 6) throw PasswordException(PASSWORD_INVALID_LENGTH)`
 * blocks scattered across UserAuthService (change + reset),
 * UserInviteService (invite accept), and PublicUserController
 * (guest set-password). Each one let a 6-character password through —
 * `aaaaaa` qualified — and the message even said so verbatim, which
 * doubled as an attacker hint ("Password could contain at least six
 * characters").
 *
 * Policy now (single source of truth):
 *  - Length ≥ 12. Matches the 2025 NIST 800-63B service-issued
 *    credential floor. We do NOT cap the upper bound — letting users
 *    pick long passphrases is the whole point.
 *  - Rejected if the password is on the WEAK_LIST below. The list is
 *    deliberately tiny — just the few candidates a 12-char minimum
 *    still admits (long sequences of one char, common keyboard
 *    walks, "Password" + obvious suffix). A real
 *    breach-corpus check via HaveIBeenPwned k-anonymity is out of
 *    scope for this fix because it introduces an external HTTPS
 *    dependency on every set-password call.
 *  - We do NOT enforce character-class rules (uppercase + digit +
 *    symbol). NIST 800-63B current guidance explicitly deprecates
 *    that approach — length + corpus check outperform forced mixing
 *    for actual security and badly degrade UX.
 *
 * Throws [PasswordException] with type `PASSWORD_INVALID_LENGTH`
 * (kept as the enum constant name to avoid breaking the frontend
 * error-code switch; the human-readable message in `ApiErrorCodes`
 * is now generic — see commit notes).
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12

    /**
     * Lowercase comparison set. Caller passes the raw password; we
     * lower-case once and check. This is not a serious breach corpus —
     * it's the bare floor of "would you let this through?" passwords
     * that satisfy the 12-char length alone (e.g. `aaaaaaaaaaaa`,
     * `qwertyuiop12`, `passwordpassword`).
     */
    private val WEAK_LIST: Set<String> = setOf(
        "aaaaaaaaaaaa",
        "111111111111",
        "123456789012",
        "qwertyuiop12",
        "qwertyuiopas",
        "qwerty123456",
        "password1234",
        "passwordpassword",
        "letmeinletmein",
        "welcomewelcome",
        "boat4youboat4you",
    )

    fun validate(password: String) {
        if (password.length < MIN_LENGTH) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH)
        }
        if (password.lowercase() in WEAK_LIST) {
            throw PasswordException(PasswordException.PasswordExceptionType.PASSWORD_INVALID_LENGTH)
        }
    }
}
