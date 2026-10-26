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

/** One visit of one visitor to one of the seven sites: where it came from, on what device, how many pages. */
@Entity
@Table(name = "cc_visit")
open class CcVisit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "visitor_id", nullable = false)
    open var visitorId: Long? = null

    @Column(name = "site_id", length = 40, nullable = false)
    open var siteId: String? = null

    @Column(name = "started_at", nullable = false)
    open var startedAt: Instant = Instant.now()

    @Column(name = "last_seen_at", nullable = false)
    open var lastSeenAt: Instant = Instant.now()

    @Column(name = "ended_at")
    open var endedAt: Instant? = null

    @Column(name = "landing_url", length = 1000)
    open var landingUrl: String? = null

    @Column(name = "referrer", length = 1000)
    open var referrer: String? = null

    @Column(name = "utm_source", length = 200)
    open var utmSource: String? = null

    @Column(name = "utm_medium", length = 200)
    open var utmMedium: String? = null

    @Column(name = "utm_campaign", length = 200)
    open var utmCampaign: String? = null

    @Column(name = "utm_term", length = 200)
    open var utmTerm: String? = null

    @Column(name = "utm_content", length = 200)
    open var utmContent: String? = null

    @Column(name = "gclid", length = 200)
    open var gclid: String? = null

    @Column(name = "fbclid", length = 200)
    open var fbclid: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    open var channel: VisitChannel = VisitChannel.OTHER

    /** The sister site the visitor came from when [channel] is SISTER. */
    @Column(name = "from_site_id", length = 40)
    open var fromSiteId: String? = null

    @Column(name = "ip", length = 45)
    open var ip: String? = null

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 2)
    open var countryCode: String? = null

    @Column(name = "locale", length = 10)
    open var locale: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "device_class", length = 10)
    open var deviceClass: DeviceClass? = null

    @Column(name = "browser", length = 40)
    open var browser: String? = null

    @Column(name = "os", length = 40)
    open var os: String? = null

    @Column(name = "user_agent", length = 400)
    open var userAgent: String? = null

    @Column(name = "is_bot", nullable = false)
    open var isBot: Boolean = false

    @Column(name = "bot_reason", length = 40)
    open var botReason: String? = null

    @Column(name = "page_count", nullable = false)
    open var pageCount: Int = 0
}
