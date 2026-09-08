package hr.workspace.boat4you.domains.campaign.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// Deliberately NOT extending AbstractEntity: these are high-volume mailing
// rows, and AbstractEntity is @Audited — Envers would demand *_revisions
// tables and write an audit row per send-status flip for no business value.

/** Permanent do-not-mail list, shared by all campaigns. */
@Entity
@Table(name = "email_suppression")
class EmailSuppression {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    var id: Long? = null

    /** Stored lowercased — lookups are case-insensitive on top anyway. */
    @Column(name = "email", nullable = false, unique = true, length = 320)
    lateinit var email: String

    @Column(name = "reason", nullable = false, length = 63)
    lateinit var reason: String

    @Column(name = "created", nullable = false, updatable = false)
    var created: Instant = Instant.now()
}

enum class CampaignRecipientStatus { PENDING, SENT, SUPPRESSED, FAILED }

/** One row per (campaign, address): the send queue with outcome. */
@Entity
@Table(name = "campaign_recipient")
class CampaignRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    var id: Long? = null

    @Column(name = "campaign", nullable = false, length = 63)
    lateinit var campaign: String

    @Column(name = "email", nullable = false, length = 320)
    lateinit var email: String

    @Column(name = "recipient_name", length = 255)
    var recipientName: String? = null

    /** Per-recipient unsubscribe token (random, stored — no crypto needed). */
    @Column(name = "token", nullable = false, unique = true, length = 63)
    lateinit var token: String

    @Column(name = "status", nullable = false, length = 31)
    @Enumerated(EnumType.STRING)
    var status: CampaignRecipientStatus = CampaignRecipientStatus.PENDING

    @Column(name = "error", columnDefinition = "TEXT")
    var error: String? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "created", nullable = false, updatable = false)
    var created: Instant = Instant.now()
}
