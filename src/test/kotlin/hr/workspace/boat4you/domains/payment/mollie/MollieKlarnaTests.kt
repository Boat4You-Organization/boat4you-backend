package hr.workspace.boat4you.domains.payment.mollie

import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Where Klarna is offered (EUR billing countries, limits) and what Mollie receives for it. */
class MollieKlarnaTests {
    private fun billing(country: String = "DE") = KlarnaBilling("Hans", "Mueller", "hans@example.com", "Hellersbergstrasse 14", "41460", "Neuss", country)

    @Test
    fun `countries - twelve euro countries, each capped by the account maximum of 10 000 EUR`() {
        val klarna = klarnaProperties()
        klarna.enabled shouldBe true
        klarna.countries.map { it.code } shouldBe listOf("AT", "BE", "DE", "FI", "FR", "NL", "ES", "GR", "IE", "IT", "PT", "SK")
        klarna.countries.first { it.code == "DE" }.maxAmountMinor shouldBe 1_000_000L
        // Klarna itself allows 14 000 EUR in the Netherlands; the account stops at 10 000.
        klarna.countries.first { it.code == "NL" }.maxAmountMinor shouldBe 1_000_000L
        klarna.countries.first { it.code == "IT" }.maxAmountMinor shouldBe 400_000L
        klarna.maxAmountMinor shouldBe 1_000_000L
    }

    @Test
    fun `requireAvailable - country and amount decide, Croatia, the UK and the USA never qualify`() {
        val klarna = klarnaProperties()
        klarna.requireAvailable(billing("DE"), 1_000_000)
        klarna.requireAvailable(billing("IT"), 400_000)
        shouldThrow<KlarnaNotAvailableException> { klarna.requireAvailable(billing("DE"), 1_000_001) }.reason shouldBe "amount"
        shouldThrow<KlarnaNotAvailableException> { klarna.requireAvailable(billing("IT"), 400_001) }.reason shouldBe "amount"
        listOf("HR", "GB", "US", "AU", "CA", "CH", "de", "").forEach {
            shouldThrow<KlarnaNotAvailableException> { klarna.requireAvailable(billing(it), 10_000) }.reason shouldBe "country"
        }
    }

    @Test
    fun `off unless switched on AND Mollie itself is configured`() {
        shouldThrow<KlarnaNotAvailableException> { klarnaProperties(enabled = false).requireAvailable(billing(), 10_000) }.reason shouldBe "disabled"
        klarnaProperties(mollie = mollieProperties(apiKey = "")).enabled shouldBe false
    }

    @Test
    fun `surcharge - blank means the card surcharge, a number overrides it, rubbish is ignored`() {
        klarnaProperties().surchargePercentOverride shouldBe null
        klarnaProperties(surchargePercent = " 0 ").surchargePercentOverride shouldBe BigDecimal("0")
        klarnaProperties(surchargePercent = "3.5").surchargePercentOverride shouldBe BigDecimal("3.5")
        klarnaProperties(surchargePercent = "five").surchargePercentOverride shouldBe null
        klarnaProperties(surchargePercent = "-1").surchargePercentOverride shouldBe null
        klarnaProperties(surchargePercent = "101").surchargePercentOverride shouldBe null
    }

    @Test
    fun `lines always add up to the payment - the fee is its own surcharge line only when charged`() {
        MolliePaymentLine.of("Charter", 267_760, 13_388, "Payment fee").let { lines ->
            lines.map { it.totalAmount.value } shouldBe listOf("2677.60", "133.88")
            lines.map { it.type } shouldBe listOf(null, "surcharge")
            lines.sumOf { it.totalAmount.toMinorOrNull()!! } shouldBe 281_148L
            lines.all { it.quantity == 1 && it.unitPrice == it.totalAmount } shouldBe true
        }
        MolliePaymentLine.of("Contract", 110_900, 0, "Payment fee").map { it.totalAmount.value } shouldBe listOf("1109.00")
    }

    @Test
    fun `billing form - trimmed, control characters removed, country upper case, the booking e-mail when the form has none`() {
        val request = KlarnaBillingRequest(" Hans\t", "Mueller\n", null, " Hellersbergstrasse\r\n14 ", " 41460 ", "Neuss", "de")
        request.toBilling("booking@example.com") shouldBe KlarnaBilling("Hans", "Mueller", "booking@example.com", "Hellersbergstrasse 14", "41460", "Neuss", "DE")
        request.copy(email = "own@example.com").toBilling("booking@example.com").email shouldBe "own@example.com"
    }
}
