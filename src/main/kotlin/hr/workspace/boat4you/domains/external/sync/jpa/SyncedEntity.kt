package hr.workspace.boat4you.domains.external.sync.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.OffsetDateTime

@Entity
@Table(name = "synced_entity")
open class SyncedEntity {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "entity_id", nullable = false)
    open var entityId: Long? = null

    /**
     * table name of an synced entity
     */
    @Size(max = 80)
    @NotNull
    @Column(name = "entity_name", nullable = false, length = 80)
    open var entityName: String? = null

    @Column(name = "sync_time")
    open var syncTime: OffsetDateTime? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "sync_job_id", nullable = false)
    open var syncJob: SyncJob? = null

    @NotNull
    @Column(name = "external_id", nullable = false)
    open var externalId: Long? = null
}
