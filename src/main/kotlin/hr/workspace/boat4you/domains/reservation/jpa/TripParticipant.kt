package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class TripParticipantRole {
    OWNER,
    GUEST,
    SKIPPER,
    CONCIERGE,
}

/**
 * A member of a trip's closed group. The `participantKey` is the secret the
 * joining device stores locally — it gates chat, album and crew-list reads
 * so someone merely guessing at URLs (or an ex-member after link
 * regeneration) sees nothing. Soft-removed so chat history keeps names.
 * Not Envers-audited (same reasoning as [TripPushSubscription]).
 */
@Entity
@Table(name = "trip_participant")
open class TripParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    @Column(name = "participant_key", length = 64, nullable = false)
    open var participantKey: String? = null

    @Column(name = "name", length = 120, nullable = false)
    open var name: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    open var role: TripParticipantRole = TripParticipantRole.GUEST

    @Column(name = "removed", nullable = false)
    open var removed: Boolean = false

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
