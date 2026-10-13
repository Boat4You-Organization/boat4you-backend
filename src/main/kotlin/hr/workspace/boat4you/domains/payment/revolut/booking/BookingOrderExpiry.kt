package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * `expire_pending_after` of a booking order (Revolut plan 2A.10 M3), defined for every case. Pure; the caller passes
 * `LocalDateTime.now()` in the JVM zone, as `OptionExpiryService` compares `optionExpiresAt`.
 *
 * Only an OPTION with a known `optionExpiresAt` caps the order lifetime (`remaining = optionExpiresAt - now`):
 * - `remaining >= 15 min` -> `min(order-ttl, remaining)` (seconds, rounded down);
 * - `0 < remaining < 15 min` -> 15 min: a checkout must survive 3-D Secure, the option ends a few minutes later anyway;
 * - `remaining <= 0` (the 6 h grace window after expiry, `OptionExpiryService`) -> order-ttl = Stripe parity.
 * OPTION_WAITING, RESERVATION and an OPTION without an expiry -> order-ttl (no cap).
 */
object BookingOrderExpiry {
    /** Shortest lifetime of an order capped by an option's expiry. */
    val MIN_OPTION_CHECKOUT: Duration = Duration.ofMinutes(15)

    fun effective(
        sysStatus: ReservationStatus?,
        optionExpiresAt: LocalDateTime?,
        now: LocalDateTime,
        orderTtl: Duration,
    ): Duration {
        if (sysStatus != ReservationStatus.OPTION || optionExpiresAt == null) return orderTtl
        val remaining = Duration.between(now, optionExpiresAt)
        return when {
            remaining >= MIN_OPTION_CHECKOUT -> minOf(orderTtl, remaining.truncatedTo(ChronoUnit.SECONDS))
            remaining > Duration.ZERO -> MIN_OPTION_CHECKOUT
            // OWNER DECISION PENDING (plan 2A.10 open question 1: allow a payment inside the 6 h grace window after the
            // option expired, or block it). Default until Mario answers = Stripe parity: Stripe takes the payment with
            // its normal session lifetime, so this order gets the normal order-ttl. Blocking would be a friendly 409 here.
            else -> orderTtl
        }
    }
}
