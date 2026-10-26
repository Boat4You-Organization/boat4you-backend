package hr.workspace.boat4you.domains.contactcenter.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One page a visit looked at. Purged after 13 months, like [CcEvent]. */
@Entity
@Table(name = "cc_page_view")
open class CcPageView {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "visit_id", nullable = false)
    open var visitId: Long? = null

    @Column(name = "url", length = 1000)
    open var url: String? = null

    @Column(name = "path", length = 500, nullable = false)
    open var path: String? = null

    @Column(name = "title", length = 300)
    open var title: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "page_type", length = 20, nullable = false)
    open var pageType: PageType = PageType.OTHER

    @Column(name = "boat_slug", length = 200)
    open var boatSlug: String? = null

    @Column(name = "viewed_at", nullable = false)
    open var viewedAt: Instant = Instant.now()

    @Column(name = "duration_ms")
    open var durationMs: Int? = null
}
