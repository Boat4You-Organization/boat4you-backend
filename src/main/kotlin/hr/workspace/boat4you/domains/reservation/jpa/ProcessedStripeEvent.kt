package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Marker row recording that a given Stripe webhook event has been processed.
 *
 * Used by [hr.workspace.boat4you.domains.reservation.service.StripePaymentService]
 * to make `handleWebhookEvent` idempotent across Stripe redeliveries
 * (F3-022 / F1-019). The primary key is Stripe's `event.id`, which is
 * guaranteed unique per delivery, so an `INSERT ON CONFLICT DO NOTHING`
 * suffices for at-most-once processing semantics.
 *
 * The insert is performed in a REQUIRES_NEW transaction so even if the
 * surrounding handler rolls back (e.g. partner API or DB failure during
 * `promoteReservationToBooking`), the event remains marked processed and
 * subsequent Stripe retries become no-ops. The trade-off — partial-failure
 * scenarios need manual reconciliation — is documented in V1_91 migration.
 */
@Entity
@Table(name = "processed_stripe_events")
open class ProcessedStripeEvent {
    @Id
    @Column(name = "event_id", length = 255, nullable = false)
    open var eventId: String? = null

    @Column(name = "event_type", length = 64, nullable = false)
    open var eventType: String? = null

    @Column(name = "processed_at", nullable = false)
    open var processedAt: Instant = Instant.now()
}
