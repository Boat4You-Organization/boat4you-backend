package hr.workspace.boat4you.domains.gdpr.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * GDPR Article 5(2) accountability log — one row per right-exercise event
 * (delete, export, …). Written from the service that performs the operation
 * after it succeeds; failure cases are intentionally not logged here, they go
 * to the regular application log instead.
 *
 * Intentionally NOT extending `AbstractEntity` — that base class brings in
 * Envers `@Audited` plus 5 audit columns (created, modified, creator_id,
 * modifier_id, entity_status). For an audit log, those would be
 * meta-audit-of-the-audit which we don't need; the action / requested_at /
 * completed_at columns on this row already capture the full event.
 */
@Entity
@Table(name = "gdpr_audit_log")
class GdprAuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0

    @Column(name = "action", columnDefinition = "VARCHAR(32)", nullable = false)
    lateinit var action: String

    @Column(name = "requested_at", columnDefinition = "TIMESTAMP", nullable = false)
    var requestedAt: Instant = Instant.now()

    @Column(name = "completed_at", columnDefinition = "TIMESTAMP", nullable = true)
    var completedAt: Instant? = null

    @Column(name = "request_ip", columnDefinition = "VARCHAR(45)", nullable = true)
    var requestIp: String? = null

    @Column(name = "request_user_agent", columnDefinition = "VARCHAR(500)", nullable = true)
    var requestUserAgent: String? = null

    @Column(name = "notes", columnDefinition = "TEXT", nullable = true)
    var notes: String? = null

    companion object {
        const val ACTION_DELETE_ACCOUNT = "DELETE_ACCOUNT"
        const val ACTION_EXPORT_DATA = "EXPORT_DATA"
    }
}
