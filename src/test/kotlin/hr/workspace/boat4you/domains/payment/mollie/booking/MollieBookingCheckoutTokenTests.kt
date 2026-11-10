package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.KlarnaBilling
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
    fun `a Klarna checkout has its own token, and a changed billing address changes it`() {
        val billing = KlarnaBilling("Hans", "Mueller", "hans@example.com", "Hellersbergstrasse 14", "41460", "Neuss", "DE")
        val key = MollieBookingCreateTx.billingFingerprint(billing)
        key shouldBe MollieBookingCreateTx.billingFingerprint(billing.copy())
        key.length shouldBe 64
        MollieBookingCreateTx.billingFingerprint(billing.copy(streetAndNumber = "Hellersbergstrasse 15")) shouldNotBe key
        // Field boundaries count: "a" + "bc" is not "ab" + "c".
        MollieBookingCreateTx.billingFingerprint(billing.copy(givenName = "Hans M", familyName = "ueller")) shouldNotBe
            MollieBookingCreateTx.billingFingerprint(billing.copy(givenName = "Hans", familyName = "M ueller"))

        fun klarnaToken(billingKey: String?) = MollieBookingCreateTx.checkoutToken(900, "7,8", 281_160, "web-key-1", 0, billingKey)
        klarnaToken(key) shouldBe klarnaToken(key)
        klarnaToken(key) shouldNotBe token()
        klarnaToken(null) shouldBe token()
    }

    @Test
    fun `without a web key the token is random`() {
        token(key = null) shouldNotBe token(key = null)
    }
}
