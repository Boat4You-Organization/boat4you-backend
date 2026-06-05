package hr.workspace.boat4you.security.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface TokenRepository : JpaRepository<TokenEntity, Long> {
    @Query(
        """
        SELECT t FROM TokenEntity t
        WHERE t.user.id = :id AND (t.isExpired = false OR t.isRevoked = false)
      """,
    )
    fun findAllValidTokenByUserId(id: Long): List<TokenEntity>

    @Query(
        """
        SELECT t FROM TokenEntity t
        WHERE t.user.id = :userId AND t.sessionGroup IS NOT NULL
          AND t.isRevoked = false AND t.isExpired = false
          AND (t.expiresAt IS NULL OR t.expiresAt > CURRENT_TIMESTAMP)
        """,
    )
    fun findActiveSessionTokens(userId: Long): List<TokenEntity>

    fun findByValue(value: String): TokenEntity?

    /** Hard-delete every token row for a user. Needed before deleting the user
     *  itself — tokens.user_id is a NO ACTION FK, so leftover rows (even the
     *  expired/revoked ones revokeAllUserTokens only flags) block the delete. */
    @Modifying
    fun deleteByUserId(userId: Long)
}
