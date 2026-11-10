package hr.workspace.boat4you.domains.reservation.service

import com.stripe.Stripe
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.checkout.SessionExpireParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The retrieve-and-expire-if-open logic for a previously issued Stripe Checkout Session, moved verbatim out of
 * `StripePaymentService` (F3-023) so the Mollie booking checkout can use it too (plan 2A.10 M1: an open Stripe
 * session on a phase must die when a Mollie payment is created for that phase). Callers never null the phase's
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
    /**
     * The same best-effort expire for the Mollie checkout (M1), called OUTSIDE a database transaction with short
     * timeouts and no retries, and skipped when this node has no Stripe key. A session that could not be expired stays
     * payable for at most 24 h; a Stripe payment for a phase that is already paid is then refused by the Stripe
     * webhook handler (office alert) instead of silently overwriting the phase.
     */
    fun expireQuickly(sessionId: String) {
        if (Stripe.apiKey.isNullOrBlank()) return
        try {
            val options =
                RequestOptions
                    .builder()
                    .setConnectTimeout(QUICK_CONNECT_TIMEOUT_MS)
                    .setReadTimeout(QUICK_READ_TIMEOUT_MS)
                    .setMaxNetworkRetries(0)
                    .build()
            val session = Session.retrieve(sessionId, options)
            if (session.status == "open") {
                session.expire(SessionExpireParams.builder().build(), options)
                logger.info("Expired open Stripe session $sessionId before a Mollie checkout")
            }
        } catch (e: Exception) {
            logger.warn("Could not expire Stripe session $sessionId before a Mollie checkout: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val QUICK_CONNECT_TIMEOUT_MS = 3000
        const val QUICK_READ_TIMEOUT_MS = 5000
    }

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
