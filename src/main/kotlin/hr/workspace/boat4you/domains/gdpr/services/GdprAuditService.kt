package hr.workspace.boat4you.domains.gdpr.services

import hr.workspace.boat4you.common.ratelimit.ClientIp
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

    /** The requester's address, never a forged `X-Forwarded-For` ([ClientIp], F1-022). IPv6 fits the 45-char column. */
    private fun resolveClientIp(request: HttpServletRequest): String? = ClientIp.of(request)
}
