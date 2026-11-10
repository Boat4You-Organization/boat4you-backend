package hr.workspace.boat4you.domains.payment.mollie.booking

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** The checkout token is part of the Mollie request body, so a repeated create of the same attempt must send the same one. */
class MollieBookingCheckoutTokenTests {
    private fun token(
        flow: Long = 900,
        phases: String = "7,8",
        cents: Long = 281_160,
        key: String? = "web-key-1",
        attempt: Long = 0,
    ) = MollieBookingCreateTx.checkoutToken(flow, phases, cents, key, attempt)

    @Test
    fun `same inputs give the same token, and every input changes it`() {
        token() shouldBe token()
        token(flow = 901) shouldNotBe token()
        token(phases = "7") shouldNotBe token()
        token(cents = 281_161) shouldNotBe token()
        token(key = "web-key-2") shouldNotBe token()
    }

    @Test
    fun `a new attempt with the same web key gets a new token`() {
        token(attempt = 1) shouldNotBe token(attempt = 0)
        token(attempt = 1) shouldBe token(attempt = 1)
    }

    @Test
    fun `without a web key the token is random`() {
        token(key = null) shouldNotBe token(key = null)
    }
}
