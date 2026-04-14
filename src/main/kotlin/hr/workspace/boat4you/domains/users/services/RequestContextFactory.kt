package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.models.DefaultRequestContext
import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.common.models.WebRequestContext
import org.springframework.stereotype.Service

@Service
class RequestContextFactory {
    fun buildRequestContext(user: UserDomainEntity): WebRequestContext = DefaultRequestContext(user)
}
