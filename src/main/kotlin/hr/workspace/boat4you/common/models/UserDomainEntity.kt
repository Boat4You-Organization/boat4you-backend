package hr.workspace.boat4you.common.models

import hr.workspace.boat4you.domains.users.jpa.UserEntity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.security.Principal

class UserDomainEntity(
    dbUser: UserEntity,
) : Principal {
    val userId: Long = dbUser.id!!
    val email: String = dbUser.email
    val authorities: List<GrantedAuthority> = dbUser.roleAssignments.map { SimpleGrantedAuthority(it.role.name) }

    override fun getName(): String = email

    fun isSystemAdmin(): Boolean {
        return authorities.any { it.authority == "SYSTEM_ADMIN" }
    }
}
