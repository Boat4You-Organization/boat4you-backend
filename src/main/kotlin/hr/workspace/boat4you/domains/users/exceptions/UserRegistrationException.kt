package hr.workspace.boat4you.domains.users.exceptions

class UserRegistrationException(
    val reason: UserRegistrationExceptionReason,
) : RuntimeException() {
    enum class UserRegistrationExceptionReason {
        USER_ALREADY_REGISTERED,
        VERIFICATION_CODE_REQUESTED_TOO_SOON,
        VERIFICATION_CODE_DOES_NOT_MATCH,
    }
}
