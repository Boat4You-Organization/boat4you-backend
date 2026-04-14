package hr.workspace.boat4you.domains.users.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface UserRepository :
    JpaRepository<UserEntity, Long>,
    JpaSpecificationExecutor<UserEntity> {
    @Query(
        """
        SELECT u FROM UserEntity u
        LEFT JOIN FETCH u.roleAssignments
        WHERE u.email = :email AND u.entityStatus = 'ACTIVE'
        """,
    )
    fun findByEmail(email: String): UserEntity?

    fun findByPasswordResetCode(passwordResetCode: String): UserEntity?

    fun existsByEmailIgnoreCase(email: String): Boolean

    fun existsByIdNotAndEmailIgnoreCase(
        id: Long,
        email: String,
    ): Boolean

    @Query(
        """
        SELECT DISTINCT u.email
        FROM UserEntity u
        JOIN u.roleAssignments ra
        JOIN ra.role r
        WHERE r.name = 'SYSTEM_ADMIN' AND u.entityStatus = 'ACTIVE'
    """,
    )
    fun findAllAdminEmailAddresses(): List<String>

    fun findByInviteCode(inviteCode: String): UserEntity?

    @Query(
        """
        SELECT u FROM UserEntity u
        WHERE u.id IN (:ids) AND u.entityStatus = 'ACTIVE'
        """,
    )
    fun findByIdIn(ids: List<Long>): List<UserEntity>
}
