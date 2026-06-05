package hr.workspace.boat4you.security.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tokens")
class TokenEntity : AbstractEntity<Long>() {
    @Column(name = "value", columnDefinition = "VARCHAR(1024)", nullable = false, unique = true)
    lateinit var value: String

    @Column(name = "is_revoked", columnDefinition = "BOOLEAN", nullable = false)
    var isRevoked: Boolean = false

    @Column(name = "is_expired", columnDefinition = "BOOLEAN", nullable = false)
    var isExpired: Boolean = false

    @Column(name = "expires_at", columnDefinition = "TIMESTAMP", nullable = true)
    var expiresAt: Instant? = null

    @Column(name = "user_agent", columnDefinition = "VARCHAR(512)", nullable = true)
    var userAgent: String? = null

    @Column(name = "ip_address", columnDefinition = "VARCHAR(64)", nullable = true)
    var ipAddress: String? = null

    @Column(name = "last_used_at", columnDefinition = "TIMESTAMP", nullable = true)
    var lastUsedAt: Instant? = null

    @Column(name = "session_group", columnDefinition = "VARCHAR(36)", nullable = true)
    var sessionGroup: String? = null

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id", columnDefinition = "BIGINT", nullable = false)
    lateinit var user: UserEntity
}
