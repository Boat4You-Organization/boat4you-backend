package hr.workspace.boat4you.domains.social.jpa

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.OrderColumn
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/** Lazy collections and the channel of a target load for a whole page of posts at once (no query per post). */
private const val BATCH = 100

/** One page / profile / channel we publish to (V9_118). Tokens are AES-GCM ciphertext (SocialCrypto); never serialized. */
@Entity
@Table(name = "social_account")
@BatchSize(size = BATCH)
open class SocialAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 20, nullable = false)
    open var platform: SocialPlatform? = null

    /** Contact Center site id (SiteIds); null = shared by all sites (our one YouTube channel). */
    @Column(name = "site", length = 40)
    open var site: String? = null

    @Column(name = "external_id", length = 200, nullable = false)
    open var externalId: String? = null

    @Column(name = "name", length = 200, nullable = false)
    open var name: String = ""

    @Column(name = "handle", length = 300)
    open var handle: String? = null

    @Column(name = "avatar_url", length = 1000)
    open var avatarUrl: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: SocialAccountStatus = SocialAccountStatus.CONNECTED

    @Column(name = "access_token_enc", columnDefinition = "TEXT")
    open var accessTokenEnc: String? = null

    @Column(name = "refresh_token_enc", columnDefinition = "TEXT")
    open var refreshTokenEnc: String? = null

    @Column(name = "token_expires_at")
    open var tokenExpiresAt: Instant? = null

    /** Network ids the connector needs (Instagram user id, LinkedIn author URN, Bluesky DID, Facebook page id…). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", nullable = false)
    open var meta: MutableMap<String, String> = mutableMapOf()

    @Column(name = "private_only", nullable = false)
    open var privateOnly: Boolean = false

    @Column(name = "last_error", length = 500)
    open var lastError: String? = null

    @Column(name = "connected_by")
    open var connectedBy: Long? = null

    @Column(name = "connected_at", nullable = false)
    open var connectedAt: Instant = Instant.now()

    /** Set when the admin disconnects: tokens wiped, hidden from the channel list, past posts keep pointing here. */
    @Column(name = "disconnected_at")
    open var disconnectedAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "social_oauth_state")
open class SocialOAuthState {
    @Id
    @Column(name = "state", length = 64)
    open var state: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 20, nullable = false)
    open var platform: SocialPlatform? = null

    @Column(name = "site", length = 40)
    open var site: String? = null

    @Column(name = "admin_user_id", nullable = false)
    open var adminUserId: Long? = null

    @Column(name = "code_verifier", length = 128)
    open var codeVerifier: String? = null

    @Column(name = "return_url", length = 500, nullable = false)
    open var returnUrl: String = ""

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

@Entity
@Table(name = "social_media")
open class SocialMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    /** Random, part of the public URL. */
    @Column(name = "token", length = 64, nullable = false, unique = true)
    open var token: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false)
    open var kind: SocialMediaKind = SocialMediaKind.IMAGE

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 20, nullable = false)
    open var origin: SocialMediaOrigin = SocialMediaOrigin.UPLOAD

    /** e.g. "yacht_image:123" for a catalogue photo, the generator's request id for a generated video. */
    @Column(name = "origin_ref", length = 200)
    open var originRef: String? = null

    /** Relative to the social media root. */
    @Column(name = "storage_path", length = 500, nullable = false)
    open var storagePath: String = ""

    /** JPEG frame of a video (ffmpeg), relative to the media root; null for photos or when ffmpeg is missing. */
    @Column(name = "thumbnail_path", length = 500)
    open var thumbnailPath: String? = null

    @Column(name = "file_name", length = 200, nullable = false)
    open var fileName: String = ""

    @Column(name = "mime_type", length = 50, nullable = false)
    open var mimeType: String = ""

    @Column(name = "size_bytes", nullable = false)
    open var sizeBytes: Long = 0

    @Column(name = "width")
    open var width: Int? = null

    @Column(name = "height")
    open var height: Int? = null

    @Column(name = "duration_sec")
    open var durationSec: Int? = null

    @Column(name = "sha256", length = 64, nullable = false)
    open var sha256: String = ""

    @Column(name = "uploaded_by")
    open var uploadedBy: Long? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

@Entity
@Table(name = "social_api_key")
open class SocialApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "name", length = 80, nullable = false)
    open var name: String = ""

    @Column(name = "prefix", length = 16, nullable = false)
    open var prefix: String = ""

    @Column(name = "secret_sha256", length = 64, nullable = false, unique = true)
    open var secretSha256: String = ""

    @Column(name = "drafts_only", nullable = false)
    open var draftsOnly: Boolean = true

    @Column(name = "created_by")
    open var createdBy: Long? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "last_used_at")
    open var lastUsedAt: Instant? = null

    @Column(name = "revoked_at")
    open var revokedAt: Instant? = null
}

@Entity
@Table(name = "social_post")
open class SocialPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "text", columnDefinition = "TEXT", nullable = false)
    open var text: String = ""

    @Column(name = "link", length = 1000)
    open var link: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: SocialPostStatus = SocialPostStatus.DRAFT

    /** The site this post is for (AI posts always; admin posts when all their channels belong to one site). */
    @Column(name = "site", length = 40)
    open var site: String? = null

    @Column(name = "scheduled_at")
    open var scheduledAt: Instant? = null

    @Column(name = "published_at")
    open var publishedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    open var source: SocialPostSource = SocialPostSource.ADMIN

    @Column(name = "note", length = 500)
    open var note: String? = null

    @Column(name = "created_by")
    open var createdBy: Long? = null

    @Column(name = "created_by_name", length = 200)
    open var createdByName: String? = null

    @Column(name = "api_key_id")
    open var apiKeyId: Long? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @Version
    @Column(name = "version", nullable = false)
    open var version: Long = 0

    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = BATCH)
    @JoinTable(
        name = "social_post_media",
        joinColumns = [JoinColumn(name = "post_id")],
        inverseJoinColumns = [JoinColumn(name = "media_id")],
    )
    @OrderColumn(name = "position")
    open var media: MutableList<SocialMedia> = mutableListOf()

    @OneToMany(mappedBy = "post", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH)
    @OrderBy("id ASC")
    open var targets: MutableList<SocialPostTarget> = mutableListOf()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "social_post_target")
open class SocialPostTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    open var post: SocialPost? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    open var account: SocialAccount? = null

    /** This channel's own text; null = the post's text. */
    @Column(name = "text", columnDefinition = "TEXT")
    open var text: String? = null

    @Column(name = "title", length = 200)
    open var title: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: SocialTargetStatus = SocialTargetStatus.PENDING

    /** When the publisher may take it: the schedule, now for "publish now", or the next retry. */
    @Column(name = "due_at")
    open var dueAt: Instant? = null

    @Column(name = "claimed_at")
    open var claimedAt: Instant? = null

    @Column(name = "external_id", length = 200)
    open var externalId: String? = null

    @Column(name = "external_url", length = 1000)
    open var externalUrl: String? = null

    @Column(name = "error", length = 1000)
    open var error: String? = null

    @Column(name = "attempts", nullable = false)
    open var attempts: Int = 0

    @Column(name = "published_at")
    open var publishedAt: Instant? = null

    /** Bumped by every change, including the publisher's raw claim: an edit loaded before the claim cannot overwrite it. */
    @Version
    @Column(name = "version", nullable = false)
    open var version: Long = 0
}

@Entity
@Table(name = "social_ai_setting")
open class SocialAiSetting {
    @Id
    @Column(name = "site", length = 40)
    open var site: String? = null

    @Column(name = "enabled", nullable = false)
    open var enabled: Boolean = false

    /** HH:mm, Europe/Zagreb. */
    @Column(name = "publish_time", length = 5, nullable = false)
    open var publishTime: String = "18:00"

    @Column(name = "language", length = 5, nullable = false)
    open var language: String = "en"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "account_ids", nullable = false)
    open var accountIds: MutableList<Long> = mutableListOf()

    @Column(name = "require_approval", nullable = false)
    open var requireApproval: Boolean = true

    @Column(name = "use_video", nullable = false)
    open var useVideo: Boolean = false

    /** Free text for the AI from us: what to push this season, what to avoid. */
    @Column(name = "guidance", columnDefinition = "TEXT")
    open var guidance: String? = null

    @Column(name = "updated_by")
    open var updatedBy: Long? = null

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()
}

@Entity
@Table(name = "social_ai_run")
open class SocialAiRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "site", length = 40, nullable = false)
    open var site: String = ""

    @Column(name = "run_date", nullable = false)
    open var runDate: LocalDate? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: SocialAiRunStatus = SocialAiRunStatus.RUNNING

    @Column(name = "topic", length = 300)
    open var topic: String? = null

    @Column(name = "post_id")
    open var postId: Long? = null

    @Column(name = "error", length = 1000)
    open var error: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "social_generation")
open class SocialGeneration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "post_id")
    open var postId: Long? = null

    @Column(name = "provider", length = 20, nullable = false)
    open var provider: String = "HIGGSFIELD"

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false)
    open var kind: SocialMediaKind = SocialMediaKind.VIDEO

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: SocialGenerationStatus = SocialGenerationStatus.QUEUED

    @Column(name = "prompt", columnDefinition = "TEXT")
    open var prompt: String? = null

    /** What to post once the video is there: `accountIds`, `site`, per-platform `text` / `title`, `schedule`. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    open var payload: MutableMap<String, Any?> = mutableMapOf()

    @Column(name = "source_media_id")
    open var sourceMediaId: Long? = null

    @Column(name = "result_media_id")
    open var resultMediaId: Long? = null

    @Column(name = "provider_request_id", length = 200)
    open var providerRequestId: String? = null

    @Column(name = "error", length = 1000)
    open var error: String? = null

    @Column(name = "attempts", nullable = false)
    open var attempts: Int = 0

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}
