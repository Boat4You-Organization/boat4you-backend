package hr.workspace.boat4you.security.jpa

import org.springframework.data.jpa.repository.JpaRepository
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

    fun findByValue(value: String): TokenEntity?
}
