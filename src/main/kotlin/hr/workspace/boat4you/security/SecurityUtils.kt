package hr.workspace.boat4you.security

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.common.models.WebRequestContext
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder

const val ANONYMOUS_USER_ID = -1L

fun checkAccessForAdminOrSelf(userOrTechnicianIdToFetch: Long) {
    val user = SecurityContextHolder.getContext().authentication.principal as UserDomainEntity

    val userId = user.userId
    val roles = user.authorities.map { it.authority }

    if (roles.contains("SYSTEM_ADMIN")) {
        return
    }

    if (userId != userOrTechnicianIdToFetch) {
        throw AccessDeniedException("Access denied for userId: $userId")
    }
}

fun checkAccessForAdminOrManagerOrSelf(userOrTechnicianIdToFetch: Long) {
    val user = SecurityContextHolder.getContext().authentication.principal as UserDomainEntity

    val userId = user.userId
    val roles = user.authorities.map { it.authority }

    if (roles.contains("SYSTEM_ADMIN") || roles.contains("MANAGER")) {
        return
    }

    if (userId != userOrTechnicianIdToFetch) {
        throw AccessDeniedException("Access denied")
    }
}

fun getAuthenticatedUserId(): Long {
    val principal = SecurityContextHolder.getContext().authentication?.principal

    // Anonymous user
    if (principal == null || principal is String) {
        return ANONYMOUS_USER_ID
    }

    val currentUser = principal as UserDomainEntity
    return currentUser.userId
}

fun getAuthenticatedUserRoleNames(): List<String> {
    val principal = SecurityContextHolder.getContext().authentication.principal

    // Anonymous user
    if (principal is String) {
        return emptyList()
    }

    val currentUser = principal as UserDomainEntity
    return currentUser.authorities.map { it.authority }
}

fun HttpServletRequest.getContextRequired(): WebRequestContext = this.getAttribute(WebRequestContext.CONTEXT_ATTRIBUTE) as WebRequestContext

fun HttpServletRequest.getContextOptional(): WebRequestContext? = this.getAttribute(WebRequestContext.CONTEXT_ATTRIBUTE) as? WebRequestContext
