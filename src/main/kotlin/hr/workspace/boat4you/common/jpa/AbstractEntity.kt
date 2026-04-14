package hr.workspace.boat4you.common.jpa

import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import org.hibernate.envers.Audited
import java.io.Serializable
import java.time.Instant

@MappedSuperclass
@Audited
abstract class AbstractEntity<ID : Serializable> {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: ID? = null

    @Column(name = "created", columnDefinition = "TIMESTAMP", updatable = false, nullable = false)
    var created: Instant = Instant.now()

    @Column(name = "modified", columnDefinition = "TIMESTAMP", updatable = true, nullable = true)
    var modified: Instant? = Instant.now()

    @Column(name = "creator_id", columnDefinition = "BIGINT", updatable = false, nullable = true)
    var creatorId: Long? = null

    @Column(name = "modifier_id", columnDefinition = "BIGINT", updatable = true, nullable = true)
    var modifierId: Long? = null

    @Column(name = "entity_status", columnDefinition = "VARCHAR(31)", nullable = false)
    @Enumerated(EnumType.STRING)
    var entityStatus: EntityStatusEnum = EntityStatusEnum.ACTIVE

    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        created = now
        modified = now

        // TODO Fill when Security has been implemented

        /*val currentUser = getAuthenticatedUserId()
        if (currentUser != ANONYMOUS_USER_ID) {
            creatorId = currentUser
            modifierId = currentUser
        }*/
    }

    @PreUpdate
    fun preUpdate() {
        modified = Instant.now()

        // TODO Fill when Security has been implemented

        /*val currentUser = getAuthenticatedUserId()
        if (currentUser != ANONYMOUS_USER_ID) {
            modifierId = currentUser
        }*/
    }
}
