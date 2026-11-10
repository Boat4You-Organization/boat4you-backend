package hr.workspace.boat4you.domains.custompayment

import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentVerdict
import hr.workspace.boat4you.domains.custompayment.service.OrderSnapshot
import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.payment.mollie.MollieLink
import hr.workspace.boat4you.domains.payment.mollie.MollieLinks
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentDetails
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/** The verdict is the only thing that can turn a row COMPLETED: every check must hold, otherwise a person reviews it. */
class CustomPaymentVerdictTests {
    private val token = "0b6f7c1e-3c55-4d1e-9a7e-1f2d3c4b5a69"
    private val at = Instant.parse("2026-10-13T10:00:00Z")

    private fun payment(
        status: String = "paid",
        amount: MollieAmount = MollieAmount.eur(110_900),
        reference: String? = token,
        details: MolliePaymentDetails? = null,
        refunded: MollieAmount? = null,
    ) = MolliePayment(
        id = "tr_7UhSN1zuXS",
        status = status,
        amount = amount,
        amountRefunded = refunded,
        metadata = reference?.let { mapOf("customPaymentToken" to it) },
        details = details,
        createdAt = at,
        paidAt = at.takeIf { status == "paid" },
        links = MollieLinks(checkout = MollieLink("https://www.mollie.com/checkout/select-method/7UhSN1zuXS")),
    )

    private fun verdict(payment: MolliePayment) = CustomPaymentVerdict.of(payment, 110_900, token)

    @Test
    fun `paid payment with every check matching - COMPLETE with the payment id`() {
        verdict(payment()) shouldBe CustomPaymentVerdict.Complete("tr_7UhSN1zuXS")
        verdict(payment(status = "PAID", amount = MollieAmount("eur", "1109.00"))) shouldBe CustomPaymentVerdict.Complete("tr_7UhSN1zuXS")
    }

    @Test
    fun `paid but anything off - NEEDS_REVIEW with the reason, never COMPLETE`() {
        val cases =
            mapOf(
                payment(amount = MollieAmount.eur(110_800)) to "amount 1108.00 instead of 110900",
                payment(amount = MollieAmount("GBP", "1109.00")) to "currency GBP",
                payment(amount = MollieAmount("EUR", "1109.001")) to "instead of 110900",
                payment(reference = "another-token") to "reference does not match",
                payment(reference = null) to "reference does not match",
            )
        cases.forEach { (payment, reason) ->
            val result = verdict(payment)
            result.shouldBeInstanceOf<CustomPaymentVerdict.NeedsReview>()
            result.reason shouldContain reason
        }
        val all = verdict(payment(amount = MollieAmount("USD", "0.01"), reference = "x")) as CustomPaymentVerdict.NeedsReview
        all.reason shouldContain "amount 0.01 instead of 110900"
        all.reason shouldContain "currency USD"
    }

    @Test
    fun `failed expired and canceled end the attempt, every other status is still pending`() {
        verdict(payment(status = "failed")) shouldBe CustomPaymentVerdict.Failed
        verdict(payment(status = "expired")) shouldBe CustomPaymentVerdict.Failed
        verdict(payment(status = "canceled")) shouldBe CustomPaymentVerdict.Cancelled
        listOf("open", "pending", "authorized", "something_new").forEach {
            verdict(payment(status = it)) shouldBe CustomPaymentVerdict.StillPending
        }
        // A wrong amount on a failed payment is irrelevant: nothing was paid.
        verdict(payment(status = "failed", amount = MollieAmount.eur(1))) shouldBe CustomPaymentVerdict.Failed
    }

    @Test
    fun `snapshot keeps the whitelisted fields only - card brand and last four, nothing about the payer`() {
        val json =
            OrderSnapshot
                .of(payment(details = MolliePaymentDetails(cardLabel = "Visa", cardNumber = "4242", cardCountryCode = "HR"), refunded = MollieAmount.eur(500)))
                .toJson()
        json shouldContain "\"card_brand\":\"Visa\""
        json shouldContain "\"card_last_four\":\"4242\""
        json shouldContain "\"id\":\"tr_7UhSN1zuXS\""
        json shouldContain "\"state\":\"paid\""
        json shouldContain "\"refunded_amount\":500"
        json shouldNotContain token
        json shouldNotContain "HR"

        val bad = OrderSnapshot.of(payment(details = MolliePaymentDetails(cardLabel = "<script>", cardNumber = "42424242"))).toJson()
        bad shouldNotContain "<script>"
        bad shouldNotContain "42424242"
    }
}
