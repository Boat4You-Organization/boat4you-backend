package hr.workspace.boat4you.common.models

interface WebRequestContext {
    val currentUser: UserDomainEntity
    val currentUserId get() = currentUser.userId

    companion object {
        const val CONTEXT_ATTRIBUTE = "requestContext"
    }
}
