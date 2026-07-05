package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A browser/device subscribed to Boat4You Trip push reminders via the
 * /trip/{token} hub. Deliberately NOT an [hr.workspace.boat4you.common.jpa.AbstractEntity]
 * (no Envers audit — same reasoning as [ProcessedStripeEvent]): high-churn
 * technical rows with no business meaning to audit.
 *
 * `isOwner` is set when the subscribing browser also carried the booking
 * owner's authenticated web session — installment reminders are sent ONLY to
 * owner subscriptions so the invited crew never learns about payments.
 */
@Entity
@Table(name = "trip_push_subscription")
open class TripPushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "reservation_id", nullable = false)
    open var reservationId: Long? = null

    /** Push-service URL — globally unique per device+site subscription. */
    @Column(name = "endpoint", nullable = false, columnDefinition = "TEXT")
    open var endpoint: String? = null

    @Column(name = "p256dh", length = 255, nullable = false)
    open var p256dh: String? = null

    @Column(name = "auth", length = 255, nullable = false)
    open var auth: String? = null

    @Column(name = "is_owner", nullable = false)
    open var isOwner: Boolean = false

    @Column(name = "user_agent", length = 255)
    open var userAgent: String? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
