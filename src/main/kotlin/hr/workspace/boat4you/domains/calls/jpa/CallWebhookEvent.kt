package hr.workspace.boat4you.domains.calls.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** A raw ElevenLabs webhook delivery (V9_113), stored before it is processed. */
@Entity
@Table(name = "call_webhook_event")
open class CallWebhookEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "conversation_id", length = 100, nullable = false)
    open var conversationId: String? = null

    @Column(name = "type", length = 40, nullable = false)
    open var type: String? = null

    @Column(name = "received_at", nullable = false)
    open var receivedAt: Instant = Instant.now()

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    open var payload: String? = null

    @Column(name = "processed_at")
    open var processedAt: Instant? = null

    @Column(name = "error", length = 500)
    open var error: String? = null
}
