package hr.workspace.boat4you.domains.users.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface UserRepository :
    JpaRepository<UserEntity, Long>,
    JpaSpecificationExecutor<UserEntity> {
    /**
     * F2-010: `LEFT JOIN FETCH u.roleAssignments` multiplies the user
     * row by the number of role-assignment rows over the wire (and
     * before Hibernate de-dupes in memory). `DISTINCT` collapses it
     * to one row per user — same end result, less wire traffic. Hit
     * once per authenticated request (every JWT filter pass), so a
     * small saving multiplied across the whole admin workload.
     */
    @Query(
        """
        SELECT DISTINCT u FROM UserEntity u
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

    /**
     * Case-insensitive email lookup used by social login (OAuthService) to
     * auto-link a verified provider email to an existing account. Mirrors
     * `findByEmail` (roles fetched, ACTIVE-only) but folds case because provider
     * emails are normalised lowercase while locally-registered emails may be
     * mixed-case.
     */
    @Query(
        """
        SELECT DISTINCT u FROM UserEntity u
        LEFT JOIN FETCH u.roleAssignments
        WHERE LOWER(u.email) = LOWER(:email) AND u.entityStatus = 'ACTIVE'
        """,
    )
    fun findByEmailIgnoreCaseWithRoles(email: String): UserEntity?

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

    fun findByUnsubscribeToken(unsubscribeToken: String): UserEntity?

    @Query(
        """
        SELECT u FROM UserEntity u
        WHERE u.id IN (:ids) AND u.entityStatus = 'ACTIVE'
        """,
    )
    fun findByIdIn(ids: List<Long>): List<UserEntity>

    /**
     * Used by `BirthdayEmailJob` to fan out the daily birthday-greeting
     * batch. Filters by month + day so the year of birth doesn't matter.
     * Excludes soft-deleted accounts (anonymized email is undeliverable
     * and we shouldn't be sending mail to a deleted user anyway).
     */
    @Query(
        value = """
        SELECT * FROM users
        WHERE birthday IS NOT NULL
        AND EXTRACT(MONTH FROM birthday) = :month
        AND EXTRACT(DAY FROM birthday) = :day
        AND deleted_at IS NULL
        AND marketing_opt_out = false
        AND entity_status = 'ACTIVE'
        """,
        nativeQuery = true,
    )
    fun findAllByBirthdayMonthDay(
        month: Int,
        day: Int,
    ): List<UserEntity>
}
