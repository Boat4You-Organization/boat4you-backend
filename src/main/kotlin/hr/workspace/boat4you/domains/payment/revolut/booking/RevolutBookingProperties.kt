package hr.workspace.boat4you.domains.payment.revolut.booking

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * `application.revolut.booking.*` — Revolut orders of ONLINE-BOOKING card payments (the Merchant API keys themselves
 * are the shared `RevolutProperties`). An invalid value never fails the boot: ERROR + the default.
 */
@Component
class RevolutBookingProperties(
    @Value("\${application.revolut.booking.order-ttl:PT1H}") orderTtl: Duration,
    @Value("\${application.revolut.booking.enforce-challenge:automatic}") enforceChallenge: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Default `expire_pending_after` of a booking order (capped further by an option's expiry). */
    val orderTtl: Duration =
        if (orderTtl.isNegative || orderTtl.isZero) {
            log.error("application.revolut.booking.order-ttl {} is not positive — using {}", orderTtl, DEFAULT_ORDER_TTL)
            DEFAULT_ORDER_TTL
        } else {
            orderTtl
        }

    /** `enforce_challenge` of a booking order: `automatic` (Revolut decides on 3DS) or `forced`. */
    val enforceChallenge: String =
        enforceChallenge.trim().lowercase().let { value ->
            if (value in ENFORCE_CHALLENGE_VALUES) {
                value
            } else {
                log.error("application.revolut.booking.enforce-challenge is not one of {} — using {}", ENFORCE_CHALLENGE_VALUES, AUTOMATIC)
                AUTOMATIC
            }
        }

    companion object {
        const val AUTOMATIC = "automatic"
        const val FORCED = "forced"
        val ENFORCE_CHALLENGE_VALUES = setOf(AUTOMATIC, FORCED)
        val DEFAULT_ORDER_TTL: Duration = Duration.ofHours(1)
    }
}
