package hr.workspace.boat4you.domains.contactcenter.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** One thing a visitor did on a page (click, chat open, nudge, consent...). `payload` is optional JSON. */
@Entity
@Table(name = "cc_event")
open class CcEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "visit_id", nullable = false)
    open var visitId: Long? = null

    @Column(name = "page_view_id")
    open var pageViewId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    open var type: CcEventType? = null

    @Column(name = "label", length = 200)
    open var label: String? = null

    @Column(name = "target", length = 300)
    open var target: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    open var payload: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
