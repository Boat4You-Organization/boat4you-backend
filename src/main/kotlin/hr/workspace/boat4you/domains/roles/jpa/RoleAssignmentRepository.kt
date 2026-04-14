package hr.workspace.boat4you.domains.roles.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface RoleAssignmentRepository : JpaRepository<RoleAssignmentEntity, Long> {
    @Modifying
    @Query("DELETE FROM RoleAssignmentEntity ra WHERE ra.user.id = :userId")
    fun deleteByUserId(userId: Long)

    @Modifying
    @Query("DELETE FROM RoleAssignmentEntity ra WHERE ra.user.id = :userId AND ra.role.id IN :roleIds")
    fun deleteRoleAssignmentsByUserIdAndRoleIdIn(
        userId: Long,
        roleIds: Set<Long>,
    )
}
