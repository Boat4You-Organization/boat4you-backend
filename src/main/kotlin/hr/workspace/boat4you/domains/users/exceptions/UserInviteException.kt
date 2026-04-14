package hr.workspace.boat4you.domains.users.exceptions

class UserInviteException(
    val type: UserInviteExceptionType,
    val userIds: List<Long> = emptyList(),
) : RuntimeException()

enum class UserInviteExceptionType {
    INVITE_ALREADY_ACCEPTED,
    INVALID_INVITE_CODE,
    INVITE_EXPIRED,
}
