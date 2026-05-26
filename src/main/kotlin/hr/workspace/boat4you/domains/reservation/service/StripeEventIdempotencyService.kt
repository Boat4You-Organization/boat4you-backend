package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.jpa.ProcessedStripeEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Owns the REQUIRES_NEW commit for Stripe event-id idempotency claims.
 *
 * Kept in a separate bean from [StripePaymentService] on purpose: Spring's
 * @Transactional only activates on cross-bean proxy calls, so the claim
 * cannot live as a self-invoked method on the webhook handler itself.
 *
 * Backed by `processed_stripe_events` (Flyway V1_91). See that migration
 * for the retention/partial-failure rationale.
 */
@Service
class StripeEventIdempotencyService(
    private val processedStripeEventRepository: ProcessedStripeEventRepository,
) {
    /**
     * Atomically claim a Stripe event id. Returns `true` on the first
     * claim, `false` if the event has already been processed.
     *
     * Runs in its own REQUIRES_NEW transaction so the claim row commits
     * independently of the surrounding webhook handler. Even if the
     * handler later rolls back, the claim survives and subsequent Stripe
     * redeliveries become safe no-ops. Trade-off: partial failures
     * (claim committed but work rolled back) require manual
     * reconciliation; see V1_91 migration comment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimEventIfNew(
        eventId: String,
        eventType: String,
    ): Boolean = processedStripeEventRepository.claimEventIfNew(eventId, eventType) == 1
}
