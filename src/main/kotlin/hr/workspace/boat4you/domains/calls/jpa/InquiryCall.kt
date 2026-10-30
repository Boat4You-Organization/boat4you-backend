package hr.workspace.boat4you.domains.calls.jpa

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** One AI call attempt (or a callback task for Mario) for an inquiry (V9_113). */
@Entity
@Table(name = "inquiry_call")
open class InquiryCall {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "inquiry_id", nullable = false)
    open var inquiryId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    open var kind: CallKind? = null

    @Column(name = "attempt", nullable = false)
    open var attempt: Int = 1

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    open var status: CallStatus? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 30)
    open var skipReason: SkipReason? = null

    @Column(name = "scheduled_at", nullable = false)
    open var scheduledAt: Instant? = null

    @Column(name = "started_at")
    open var startedAt: Instant? = null

    @Column(name = "ended_at")
    open var endedAt: Instant? = null

    @Column(name = "duration_secs")
    open var durationSecs: Int? = null

    @Column(name = "language", length = 5)
    open var language: String? = null

    @Column(name = "to_number", length = 20, nullable = false)
    open var toNumber: String? = null

    @Column(name = "from_number", length = 20)
    open var fromNumber: String? = null

    @Column(name = "elevenlabs_conversation_id", length = 100, unique = true)
    open var elevenlabsConversationId: String? = null

    @Column(name = "twilio_call_sid", length = 64)
    open var twilioCallSid: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    open var outcome: CallOutcome? = null

    @Column(name = "summary", columnDefinition = "TEXT")
    open var summary: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data")
    open var data: Map<String, Any?>? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transcript")
    open var transcript: List<Map<String, Any?>>? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality")
    open var quality: Map<String, Any?>? = null

    @Column(name = "audio_path", length = 255)
    open var audioPath: String? = null

    @Column(name = "cost_credits")
    open var costCredits: Int? = null

    @Column(name = "termination_reason", length = 200)
    open var terminationReason: String? = null

    @Column(name = "error", length = 500)
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
