package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProcessedStripeEventRepository : JpaRepository<ProcessedStripeEvent, String> {
    /**
     * Atomic claim of a Stripe event id. Returns `1` if this is the first
     * time we are processing the event, `0` if a row already exists.
     *
     * Uses Postgres `INSERT ... ON CONFLICT DO NOTHING` so the check is
     * race-condition-free even under concurrent Stripe redeliveries hitting
     * different application instances.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO processed_stripe_events (event_id, event_type, processed_at)
            VALUES (:eventId, :eventType, now())
            ON CONFLICT (event_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun claimEventIfNew(
        @Param("eventId") eventId: String,
        @Param("eventType") eventType: String,
    ): Int
}
