package hr.workspace.boat4you.domains.pr.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// PR module (digital-PR outreach, V9_121). Not audited (no Envers): no *_revisions tables. Links between tables are plain
// id columns, as in SocialAiRun.postId, so services load exactly what they need and nothing lazy leaks into DTOs.

/** A medium we pitch to; market is ISO-3166 alpha-2 (GB, not UK). Unique on (lower(name), market). */
@Entity
@Table(name = "pr_outlet")
open class PrOutlet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "name", length = 200, nullable = false)
    open var name: String = ""

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "market", length = 2, nullable = false)
    open var market: String? = null

    /** 1 = national, 3 = niche; CHECK 1..3. */
    @Column(name = "tier", nullable = false)
    open var tier: Short? = null

    @Column(name = "section_url", length = 500)
    open var sectionUrl: String? = null

    @Column(name = "desk_contact", length = 320)
    open var deskContact: String? = null

    @Column(name = "notes", columnDefinition = "TEXT")
    open var notes: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

/**
 * A journalist (or desk) at an outlet. email is stored normalised (trim + lowercase); the same address may exist once per
 * outlet (unique on lower(email), coalesce(outlet_id, 0)), and an opt-out or bounce of one row applies to all of them.
 */
@Entity
@Table(name = "pr_contact")
open class PrContact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "outlet_id")
    open var outletId: Long? = null

    @Column(name = "name", length = 200)
    open var name: String? = null

    @Column(name = "role", length = 200)
    open var role: String? = null

    @Column(name = "email", length = 320, nullable = false)
    open var email: String = ""

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "market", length = 2)
    open var market: String? = null

    /** Where the address was published; without it (and email_found_on) the contact cannot be mailed. */
    @Column(name = "email_source_url", length = 1000)
    open var emailSourceUrl: String? = null

    @Column(name = "email_found_on")
    open var emailFoundOn: LocalDate? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: PrContactStatus = PrContactStatus.ACTIVE

    @Column(name = "recent_article_url", length = 1000)
    open var recentArticleUrl: String? = null

    @Column(name = "notes", columnDefinition = "TEXT")
    open var notes: String? = null

    @Column(name = "last_contacted_at")
    open var lastContactedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

/** One of our own-data studies (number is the program's running number); site_id is the BrandRegistry site. */
@Entity
@Table(name = "pr_study")
open class PrStudy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "number", nullable = false, unique = true)
    open var number: Int? = null

    @Column(name = "site_id", length = 40, nullable = false)
    open var siteId: String = ""

    @Column(name = "title", length = 300, nullable = false)
    open var title: String = ""

    @Column(name = "page_url", length = 1000)
    open var pageUrl: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: PrStudyStatus = PrStudyStatus.DRAFT

    @Column(name = "published_on")
    open var publishedOn: LocalDate? = null

    @Column(name = "notes", columnDefinition = "TEXT")
    open var notes: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

/**
 * One 1:1 plain-text email to one contact. A main pitch has parent_pitch_id null (one per contact and study); a follow-up
 * points at its main pitch (at most one per parent). message_id is the Message-ID header we generated and sent.
 */
@Entity
@Table(name = "pr_pitch")
open class PrPitch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "study_id")
    open var studyId: Long? = null

    @Column(name = "contact_id", nullable = false)
    open var contactId: Long? = null

    @Column(name = "parent_pitch_id")
    open var parentPitchId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "template", length = 20, nullable = false)
    open var template: PrPitchTemplate? = null

    @Column(name = "subject", length = 300, nullable = false)
    open var subject: String = ""

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    open var body: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: PrPitchStatus = PrPitchStatus.DRAFT

    @Column(name = "message_id", length = 300)
    open var messageId: String? = null

    @Column(name = "sent_at")
    open var sentAt: Instant? = null

    @Column(name = "error", columnDefinition = "TEXT")
    open var error: String? = null

    /** Typed in by hand: the module never reads the inbox. */
    @Column(name = "reply_note", columnDefinition = "TEXT")
    open var replyNote: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

/**
 * An article that used our study, and the state of its link to us from the last check. @DynamicUpdate: an admin edit
 * writes only the columns it changed, so it cannot undo a link check stored meanwhile (PrPlacementRepository.recordCheck).
 */
@Entity
@Table(name = "pr_placement")
@DynamicUpdate
open class PrPlacement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "study_id")
    open var studyId: Long? = null

    @Column(name = "outlet_id")
    open var outletId: Long? = null

    @Column(name = "pitch_id")
    open var pitchId: Long? = null

    @Column(name = "article_url", length = 1000, nullable = false, unique = true)
    open var articleUrl: String = ""

    @Column(name = "published_on")
    open var publishedOn: LocalDate? = null

    @Column(name = "target_url", length = 1000)
    open var targetUrl: String? = null

    @Column(name = "anchor_text", length = 500)
    open var anchorText: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "link_state", length = 20, nullable = false)
    open var linkState: PrLinkState = PrLinkState.NOT_CHECKED

    @Column(name = "last_http_status")
    open var lastHttpStatus: Int? = null

    @Column(name = "last_checked_at")
    open var lastCheckedAt: Instant? = null

    @Column(name = "alert", nullable = false)
    open var alert: Boolean = false

    @Column(name = "alert_reason", length = 200)
    open var alertReason: String? = null

    /** Open PageRank page_rank_decimal of the article's domain; null when the key is unset or the call failed. */
    @Column(name = "domain_rank", precision = 5, scale = 2)
    open var domainRank: BigDecimal? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

/** An RSS feed (Google Alerts or other) we read for mentions. */
@Entity
@Table(name = "pr_alert_feed")
open class PrAlertFeed {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "name", length = 200, nullable = false)
    open var name: String = ""

    @Column(name = "url", length = 1000, nullable = false, unique = true)
    open var url: String = ""

    @Column(name = "enabled", nullable = false)
    open var enabled: Boolean = true

    @Column(name = "last_fetched_at")
    open var lastFetchedAt: Instant? = null

    @Column(name = "last_error", columnDefinition = "TEXT")
    open var lastError: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

/** A page that names us; url is stored normalised (redirect unwrapped, utm_ dropped) and unique. */
@Entity
@Table(name = "pr_mention")
open class PrMention {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "url", length = 1000, nullable = false, unique = true)
    open var url: String = ""

    @Column(name = "title", length = 500)
    open var title: String? = null

    @Column(name = "source", length = 20, nullable = false)
    open var source: String = ""

    @Column(name = "feed_id")
    open var feedId: Long? = null

    /** null = not checked yet. */
    @Column(name = "links_to_us")
    open var linksToUs: Boolean? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: PrMentionStatus = PrMentionStatus.NEW

    @Column(name = "found_at", nullable = false)
    open var foundAt: Instant = Instant.now()

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
