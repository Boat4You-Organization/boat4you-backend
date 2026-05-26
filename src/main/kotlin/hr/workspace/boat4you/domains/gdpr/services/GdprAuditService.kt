package hr.workspace.boat4you.domains.gdpr.services

import hr.workspace.boat4you.domains.gdpr.jpa.GdprAuditLogEntity
import hr.workspace.boat4you.domains.gdpr.jpa.GdprAuditLogRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Records GDPR right-exercise events for accountability (Article 5(2)).
 *
 * Always uses `Propagation.REQUIRES_NEW` so the audit row commits even if the
 * caller's transaction subsequently rolls back — we want to know "user X
 * tried to do Y" even when Y partially failed and was undone. Service is
 * deliberately tiny so any new GDPR endpoint can drop in `auditService.log(...)`
 * one-liner without ceremony.
 */
@Service
class GdprAuditService(
    private val repository: GdprAuditLogRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun log(
        userId: Long,
        action: String,
        request: HttpServletRequest? = null,
        notes: String? = null,
        completedAt: Instant? = Instant.now(),
    ): GdprAuditLogEntity {
        val entity = GdprAuditLogEntity().apply {
            this.userId = userId
            this.action = action
            this.requestedAt = Instant.now()
            this.completedAt = completedAt
            this.requestIp = request?.let(::resolveClientIp)
            this.requestUserAgent = request?.getHeader("User-Agent")?.take(500)
            this.notes = notes
        }
        return repository.save(entity)
    }

    /**
     * Walks `X-Forwarded-For` (first hop = original client) and falls back to
     * the connection-level remote address. Trusted proxy headers only — if
     * we ever change reverse proxy, revisit. IPv6 fits the 45-char column.
     */
    private fun resolveClientIp(request: HttpServletRequest): String? {
        val xff = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
        return xff?.takeIf { it.isNotBlank() } ?: request.remoteAddr
    }
}
