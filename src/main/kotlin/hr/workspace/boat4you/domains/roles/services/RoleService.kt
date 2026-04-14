package hr.workspace.boat4you.domains.roles.services

import hr.workspace.boat4you.domains.roles.jpa.RoleEntity
import hr.workspace.boat4you.domains.roles.jpa.RoleRepository
import org.openapitools.model.UserRole
import org.springframework.stereotype.Service

@Service
class RoleService(
    private val roleRepository: RoleRepository,
) {
    fun checkIfRolesExistAndReturnDbRoles(userRoles: List<UserRole>): Set<RoleEntity> {
        val roleEntityNames = userRoles.map { it.toRoleEntity().name }.toSet()
        val dbRoleEntities = roleRepository.findByNameIn(roleEntityNames.toSet())
        if (roleEntityNames.size != dbRoleEntities.size) {
            val missingRoles = roleEntityNames - dbRoleEntities.map { it.name }.toSet()
            throw IllegalArgumentException("Roles $missingRoles do not exist")
        }
        return dbRoleEntities
    }
}
