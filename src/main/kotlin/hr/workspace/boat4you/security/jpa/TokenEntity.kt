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

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id", columnDefinition = "BIGINT", nullable = false)
    lateinit var user: UserEntity
}
