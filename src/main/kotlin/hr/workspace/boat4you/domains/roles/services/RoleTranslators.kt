package hr.workspace.boat4you.domains.roles.services

import hr.workspace.boat4you.domains.roles.jpa.RoleEntity
import hr.workspace.boat4you.domains.roles.jpa.RoleJpaEnum
import org.openapitools.model.RoleEnum
import org.openapitools.model.UserRole

fun RoleEntity.toRoleModel(): UserRole = roleNameToUserRoleModel(this.name)

fun roleNameToUserRoleModel(roleName: String) =
    when (roleName) {
        "SYSTEM_ADMIN" -> UserRole(RoleEnum.SYSTEM_ADMIN)
        "MANAGER" -> UserRole(RoleEnum.MANAGER)
        "USER" -> UserRole(RoleEnum.USER)
        else -> throw IllegalArgumentException("Role name $roleName not recognized")
    }

@Suppress("CyclomaticComplexMethod")
fun UserRole.toRoleEntity(): RoleEntity {
    val roleName =
        when (this.roleName) {
            RoleEnum.SYSTEM_ADMIN -> RoleJpaEnum.SYSTEM_ADMIN.name
            RoleEnum.MANAGER -> RoleJpaEnum.MANAGER.name
            RoleEnum.USER -> RoleJpaEnum.USER.name
        }
    return RoleEntity().apply { name = roleName }
}
