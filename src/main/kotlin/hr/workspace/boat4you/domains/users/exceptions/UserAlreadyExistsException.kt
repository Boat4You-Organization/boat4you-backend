package hr.workspace.boat4you.domains.users.exceptions

class UserAlreadyExistsException(
    val existingProperties: List<String>,
) : RuntimeException()
