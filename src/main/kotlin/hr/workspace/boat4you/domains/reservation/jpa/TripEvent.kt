package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Append-only Boat4You Trip analytics row (hub view, install, push open,
 * boat-page click, …). Not Envers-audited — pure telemetry, no PII.
 */
@Entity
@Table(name = "trip_event")
open class TripEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    @Column(name = "event_type", length = 40, nullable = false)
    open var eventType: String? = null

    @Column(name = "meta", length = 255)
    open var meta: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
