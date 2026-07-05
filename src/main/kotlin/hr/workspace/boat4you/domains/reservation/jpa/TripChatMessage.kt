package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One trip-chat message. PG is the source of truth; live delivery is an
 * in-memory SSE fan-out on the API node. `automationTag` marks scheduled
 * concierge posts (itinerary_t14 / ready_t1) so job re-runs stay idempotent.
 */
@Entity
@Table(name = "trip_chat_message")
open class TripChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    @Column(name = "participant_id")
    open var participantId: Long? = null

    @Column(name = "sender_name", length = 120, nullable = false)
    open var senderName: String? = null

    @Column(name = "sender_role", length = 20, nullable = false)
    open var senderRole: String? = null

    @Column(name = "body", length = 2000, nullable = false)
    open var body: String? = null

    @Column(name = "automation_tag", length = 40)
    open var automationTag: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
