package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/** Plan 2A.10 M3: the lifetime of a booking order, one assertion per branch (fixed clock). */
class BookingOrderExpiryTests {
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 13, 18, 0, 0, 250_000_000)
    private val ttl: Duration = Duration.ofHours(1)

    private fun option(remaining: Duration) = BookingOrderExpiry.effective(ReservationStatus.OPTION, now.plus(remaining), now, ttl)

    @Test
    fun `OPTION with at least 15 minutes left - min(order-ttl, remaining), seconds rounded down`() {
        option(Duration.ofHours(3)) shouldBe ttl
        option(Duration.ofHours(1)) shouldBe ttl
        option(Duration.ofMinutes(40).plusSeconds(30).plusMillis(700)) shouldBe Duration.ofMinutes(40).plusSeconds(30)
        option(Duration.ofMinutes(15)) shouldBe Duration.ofMinutes(15)
        BookingOrderExpiry.effective(ReservationStatus.OPTION, now.plusMinutes(40), now, Duration.ofMinutes(3)) shouldBe Duration.ofMinutes(3)
        option(Duration.ofMinutes(40)).toString() shouldBe "PT40M"
    }

    @Test
    fun `OPTION with less than 15 minutes left - the 15-minute floor (a checkout must survive 3-D Secure)`() {
        option(Duration.ofMinutes(15).minusSeconds(1)) shouldBe Duration.ofMinutes(15)
        option(Duration.ofMinutes(5)) shouldBe Duration.ofMinutes(15)
        option(Duration.ofSeconds(1)) shouldBe Duration.ofMinutes(15)
        option(Duration.ofMinutes(5)).toString() shouldBe "PT15M"
    }

    @Test
    fun `OPTION already expired (the 6 h grace window) - order-ttl, Stripe parity until the owner decides`() {
        option(Duration.ZERO) shouldBe ttl
        option(Duration.ofSeconds(-1)) shouldBe ttl
        option(Duration.ofHours(-5)) shouldBe ttl
    }

    @Test
    fun `no cap - OPTION_WAITING, RESERVATION, an OPTION without expiry, unknown status`() {
        listOf(ReservationStatus.OPTION_WAITING, ReservationStatus.RESERVATION, ReservationStatus.CANCELLED, ReservationStatus.UNKNOWN, null).forEach {
            BookingOrderExpiry.effective(it, now.plusMinutes(5), now, ttl) shouldBe ttl
        }
        BookingOrderExpiry.effective(ReservationStatus.OPTION, null, now, ttl) shouldBe ttl
    }
}
