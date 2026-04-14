package hr.workspace.boat4you.domains.external.sync.jpa

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface ServiceCallCacheRepository : JpaRepository<ServiceCallCache, Long> {
    @Query(
        """
        SELECT MAX(scc.createdAt) FROM ServiceCallCache scc
        WHERE scc.method = :method
        AND scc.hashCode = :hashCode
    """,
    )
    fun findByMethodAndHashCode(
        method: MethodCacheEnum,
        hashCode: Long,
    ): Instant?
}
