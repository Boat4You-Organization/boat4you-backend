package hr.workspace.boat4you.common.models

class DefaultRequestContext(
    override val currentUser: UserDomainEntity,
) : WebRequestContext
