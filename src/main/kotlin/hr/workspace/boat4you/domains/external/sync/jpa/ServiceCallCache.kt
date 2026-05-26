package hr.workspace.boat4you.domains.external.sync.jpa

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import java.time.Instant

@Entity
@Table(
    name = "service_call_cache",
)
open class ServiceCallCache {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "method", nullable = false)
    open var method: MethodCacheEnum? = null

    @NotNull
    @Column(name = "hash_code", nullable = false)
    open var hashCode: Long? = null

    @NotNull
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant? = null
}
