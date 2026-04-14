package hr.workspace.boat4you.security.exceptions

class PasswordException(
    val type: PasswordExceptionType,
) : Exception() {
    enum class PasswordExceptionType {
        PASSWORD_RESET_INVALID,
        PASSWORD_INVALID_LENGTH,
        OLD_PASSWORD_INVALID,
    }
}
