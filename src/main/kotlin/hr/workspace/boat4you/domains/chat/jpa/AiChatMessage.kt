package hr.workspace.boat4you.domains.chat.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One transcript line. `payload` carries optional structured extras the web
 * widget renders (yacht result cards) as serialized JSON — the text remains
 * the canonical transcript for both the model history and the admin inbox.
 */
@Entity
@Table(name = "ai_chat_message")
open class AiChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "session_id", nullable = false)
    open var sessionId: Long? = null

    @Column(name = "role", length = 20, nullable = false)
    open var role: String? = null

    @Column(name = "content", nullable = false)
    open var content: String? = null

    @Column(name = "payload")
    open var payload: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_SYSTEM = "SYSTEM"
    }
}
