package hr.workspace.boat4you.domains.gdpr.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface GdprAuditLogRepository : JpaRepository<GdprAuditLogEntity, Long> {
    fun findAllByUserIdOrderByRequestedAtDesc(userId: Long): List<GdprAuditLogEntity>
}
