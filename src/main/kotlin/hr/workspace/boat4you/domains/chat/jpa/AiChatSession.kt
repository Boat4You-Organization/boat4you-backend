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
 * broker replies from the admin inbox.
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

    companion object {
        const val STATUS_AI = "AI"
        const val STATUS_HUMAN_REQUESTED = "HUMAN_REQUESTED"
        const val STATUS_HUMAN = "HUMAN"
        const val STATUS_CLOSED = "CLOSED"
    }
}
