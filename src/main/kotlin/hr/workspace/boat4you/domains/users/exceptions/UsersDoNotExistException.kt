package hr.workspace.boat4you.domains.users.exceptions

class UsersDoNotExistException(
    val userIds: List<Long>,
) : RuntimeException()
