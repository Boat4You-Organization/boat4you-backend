package hr.workspace.boat4you.domains.payment.mollie.booking

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * `application.mollie.booking.*` — Mollie payments of ONLINE-BOOKING card checkouts (the API key itself is the shared
 * `MollieProperties`). An invalid value never fails the boot: ERROR + the default.
 */
@Component
class MollieBookingProperties(
    @Value("\${application.mollie.booking.checkout-ttl:PT15M}") checkoutTtl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * How long a created checkout counts as open on our side (ledger `expires_at`): it is handed out again for the same
     * charge only while at least [ReservationCardOrderTx.REUSE_MARGIN] of it is left. Mollie decides the real expiry
     * of a card payment; a payment that is still paid later is picked up by the webhook all the same.
     */
    val checkoutTtl: Duration =
        if (checkoutTtl.isNegative || checkoutTtl.isZero) {
            log.error("application.mollie.booking.checkout-ttl {} is not positive — using {}", checkoutTtl, DEFAULT_CHECKOUT_TTL)
            DEFAULT_CHECKOUT_TTL
        } else {
            checkoutTtl
        }

    companion object {
        val DEFAULT_CHECKOUT_TTL: Duration = Duration.ofMinutes(15)
    }
}
