package hr.workspace.boat4you.security.exceptions

class InternalLoginException(
    val type: Type,
    val email: String,
) : Exception("type=$type, email=$email") {
    enum class Type {
        BAD_CREDENTIALS,
        MAX_ATTEMPTS_EXCEEDED,
        USER_DOES_NOT_EXIST,
        USER_INVITE_NOT_ACCEPTED,

        /** Two-step sign-in is on for this account: only the admin sign-in (/auth/mfa/login) issues its tokens. */
        MFA_REQUIRED,
    }
}
