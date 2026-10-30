package hr.workspace.boat4you.domains.contactcenter.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

/**
 * One row of the Contact Center security log: an IRIS lock, an AI circuit breaker transition, or (written by P1b's
 * `TrackerSecurityLog` through plain JDBC) a dropped tracker surge or bot burst. Evidence: no job deletes it.
 * `site_id` NULL = global (a breaker over all sites, or the tracker writer queue).
 */
@Entity
@Table(name = "cc_security_event")
open class CcSecurityEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    /** One of the `TYPE_*` constants; the empty default is refused by `ck_cc_security_event_type`, never stored. */
    @Column(name = "type", length = 30, nullable = false)
    open var type: String = ""

    @Column(name = "site_id", length = 40)
    open var siteId: String? = null

    @Column(name = "detail", columnDefinition = "TEXT")
    open var detail: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    companion object {
        const val TYPE_LOCK = "LOCK"
        const val TYPE_BREAKER_OPEN = "BREAKER_OPEN"
        const val TYPE_BREAKER_CLOSE = "BREAKER_CLOSE"
        const val TYPE_SURGE_DROPPED = "SURGE_DROPPED"
        const val TYPE_BOT_BURST = "BOT_BURST"
    }
}

interface CcSecurityEventRepository : JpaRepository<CcSecurityEvent, Long> {
    fun findAllByOrderByIdDesc(pageable: Pageable): List<CcSecurityEvent>

    /** The newest BREAKER_OPEN / BREAKER_CLOSE of every scope (site_id NULL = global): restores a manual open after a restart. */
    @Query(
        value =
            "SELECT DISTINCT ON (COALESCE(site_id, '*')) * FROM cc_security_event WHERE type IN ('BREAKER_OPEN', 'BREAKER_CLOSE') " +
                "ORDER BY COALESCE(site_id, '*'), id DESC",
        nativeQuery = true,
    )
    fun latestBreakerEventPerScope(): List<CcSecurityEvent>
}
