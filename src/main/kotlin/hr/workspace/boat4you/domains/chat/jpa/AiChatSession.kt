package hr.workspace.boat4you.domains.chat.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One visitor conversation with the boat4you AI assistant. The `token` is the
 * anonymous browser credential (localStorage); `status` decides who answers:
 * AI until the visitor asks for a person, then HUMAN_REQUESTED -> HUMAN once a
 * broker replies from the admin inbox; LOCKED when the security guard stopped the AI.
 */
@Entity
@Table(name = "ai_chat_session")
open class AiChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "token", length = 64, nullable = false)
    open var token: String? = null

    @Column(name = "locale", length = 8, nullable = false)
    open var locale: String = "en"

    @Column(name = "status", length = 20, nullable = false)
    open var status: String = STATUS_AI

    @Column(name = "visitor_name", length = 120)
    open var visitorName: String? = null

    @Column(name = "visitor_email", length = 255)
    open var visitorEmail: String? = null

    @Column(name = "admin_unread", nullable = false)
    open var adminUnread: Boolean = false

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "last_activity_at", nullable = false)
    open var lastActivityAt: Instant = Instant.now()

    /** Last widget heartbeat — "live on the site" = within ~90 s of now. */
    @Column(name = "last_seen_at")
    open var lastSeenAt: Instant? = null

    @Column(name = "current_page", length = 500)
    open var currentPage: String? = null

    /** document.referrer at session creation — where the visitor came from. */
    @Column(name = "referrer", length = 500)
    open var referrer: String? = null

    /** JSON array of recent pages (newest last, capped in the service). */
    @Column(name = "page_trail")
    open var pageTrail: String? = null

    @Column(name = "ip", length = 45)
    open var ip: String? = null

    @Column(name = "country_code", length = 2)
    open var countryCode: String? = null

    @Column(name = "country", length = 80)
    open var country: String? = null

    /**
     * Security guard (11.10.2026): a hard rule or two soft rules hit - a visitor message, the name or the model's
     * reply. The session is evidence from then on: the AI never answers it again and it cannot be deleted.
     */
    @Column(name = "flagged", nullable = false)
    open var flagged: Boolean = false

    /** "<direction>:<category>:<rule>" of the first hit, e.g. "IN:FORMAT:J1", "NAME:MARKUP:H1", "OUT:OUTPUT_LEAK:OUT_CANARY". */
    @Column(name = "lock_reason", length = 120)
    open var lockReason: String? = null

    @Column(name = "locked_at")
    open var lockedAt: Instant? = null

    /** Bit mask of the distinct soft guard rules this session has hit (ChatSecurityGuard.classify). */
    @Column(name = "security_soft_hits", nullable = false)
    open var securitySoftHits: Int = 0

    /** Broker delete is a soft delete (review B9/A5): the row stays, the inbox and the widget no longer see it. */
    @Column(name = "deleted_at")
    open var deletedAt: Instant? = null

    companion object {
        const val STATUS_AI = "AI"
        const val STATUS_HUMAN_REQUESTED = "HUMAN_REQUESTED"
        const val STATUS_HUMAN = "HUMAN"
        const val STATUS_CLOSED = "CLOSED"

        /** The guard stopped the AI and a live agent takes over; never back to AI (a broker reply makes it HUMAN). */
        const val STATUS_LOCKED = "LOCKED"
    }
}
