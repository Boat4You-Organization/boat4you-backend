package hr.workspace.boat4you.domains.reservation.service

import com.stripe.model.checkout.Session
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The retrieve-and-expire-if-open logic for a previously issued Stripe Checkout Session, moved verbatim out of
 * `StripePaymentService` (F3-023) so the Revolut booking checkout can use it too (Revolut plan 2A.10 M1: an open Stripe
 * session on a phase must die when a Revolut order is created for that phase). Callers never null the phase's
 * `stripeSessionId` because of it (history).
 */
@Component
class StripeSessionExpirer {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    /**
     * Best-effort expire of a previously-issued Stripe Checkout Session.
     *
     * Stripe rejects `expire` on terminal sessions (status `complete` or
     * `expired`); we retrieve first and only call expire while still
     * `open`. Network or API errors are logged but swallowed — the
     * caller already has a new sessionId to stamp on the phase and
     * blocking that on a Stripe API hiccup would be worse than the rare
     * orphan-open-session it leaves behind.
     */
    fun expireStripeSessionSafely(oldSessionId: String) {
        try {
            val oldSession = Session.retrieve(oldSessionId)
            if (oldSession.status == "open") {
                oldSession.expire()
                logger.info("Expired prior open Stripe session $oldSessionId after reissue")
            }
        } catch (e: Exception) {
            logger.warn("Could not expire prior Stripe session $oldSessionId: ${e.message}")
        }
    }
}
