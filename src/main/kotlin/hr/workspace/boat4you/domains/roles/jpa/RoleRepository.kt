package hr.workspace.boat4you.domains.roles.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RoleRepository : JpaRepository<RoleEntity, Long> {
    fun findByNameIn(names: Set<String>): Set<RoleEntity>
}
