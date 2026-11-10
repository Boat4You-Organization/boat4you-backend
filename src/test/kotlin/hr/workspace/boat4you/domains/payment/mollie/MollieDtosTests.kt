package hr.workspace.boat4you.domains.payment.mollie

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MollieDtosTests {
    @Test
    fun `eur formats minor units as a two-decimal string`() {
        MollieAmount.eur(110_900) shouldBe MollieAmount("EUR", "1109.00")
        MollieAmount.eur(5) shouldBe MollieAmount("EUR", "0.05")
        MollieAmount.eur(0).value shouldBe "0.00"
    }

    @Test
    fun `toMinorOrNull parses plain decimals with at most two decimals`() {
        MollieAmount("EUR", "1109.00").toMinorOrNull() shouldBe 110_900L
        MollieAmount("EUR", "10.5").toMinorOrNull() shouldBe 1_050L
        MollieAmount("EUR", "10").toMinorOrNull() shouldBe 1_000L
    }

    @Test
    fun `toMinorOrNull rejects fractions of a cent and garbage`() {
        MollieAmount("EUR", "1.234").toMinorOrNull() shouldBe null
        MollieAmount("EUR", "abc").toMinorOrNull() shouldBe null
        MollieAmount("EUR", "").toMinorOrNull() shouldBe null
    }

    @Test
    fun `payment ids must look like tr_ tokens`() {
        MollieClient.isValidPaymentId("tr_7UhSN1zuXS") shouldBe true
        MollieClient.isValidPaymentId("ord_123") shouldBe false
        MollieClient.isValidPaymentId("tr_a/b") shouldBe false
        MollieClient.isValidPaymentId(null) shouldBe false
    }
}
