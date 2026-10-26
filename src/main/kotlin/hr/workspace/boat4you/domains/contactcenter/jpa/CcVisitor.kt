package hr.workspace.boat4you.domains.contactcenter.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One browser, told apart by the `visitor_key` the sites keep in a first-party cookie / localStorage. */
@Entity
@Table(name = "cc_visitor")
open class CcVisitor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "visitor_key", length = 64, nullable = false)
    open var visitorKey: String? = null

    @Column(name = "first_site_id", length = 40, nullable = false)
    open var firstSiteId: String? = null

    @Column(name = "contact_id")
    open var contactId: Long? = null

    /** Our own staff browsing: kept out of the statistics. */
    @Column(name = "is_staff", nullable = false)
    open var isStaff: Boolean = false

    @Column(name = "visit_count", nullable = false)
    open var visitCount: Int = 0

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "last_seen_at", nullable = false)
    open var lastSeenAt: Instant = Instant.now()
}
