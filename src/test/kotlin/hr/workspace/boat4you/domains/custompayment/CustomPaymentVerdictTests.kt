package hr.workspace.boat4you.domains.custompayment

import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentVerdict
import hr.workspace.boat4you.domains.custompayment.service.OrderSnapshot
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantOrderData
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutPayment
import hr.workspace.boat4you.domains.payment.revolut.RevolutPaymentMethod
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

    private fun order(
        state: String = "completed",
        type: String = "payment",
        amount: Long = 110_900,
        currency: String = "EUR",
        outstanding: Long? = 0,
        reference: String? = token,
        payments: List<RevolutPayment> = listOf(RevolutPayment("pay-1", "completed", at)),
    ) = RevolutOrder(
        id = "ord-1",
        type = type,
        state = state,
        amount = amount,
        currency = currency,
        outstandingAmount = outstanding,
        merchantOrderData = RevolutMerchantOrderData(reference),
        payments = payments,
        createdAt = at,
        updatedAt = at,
    )

    private fun verdict(order: RevolutOrder) = CustomPaymentVerdict.of(order, 110_900, token)

    @Test
    fun `completed payment with every check matching - COMPLETE with the successful payment id`() {
        verdict(order()) shouldBe CustomPaymentVerdict.Complete("pay-1")
        verdict(order(outstanding = null)) shouldBe CustomPaymentVerdict.Complete("pay-1")
        verdict(order(state = "COMPLETED", currency = "eur")) shouldBe CustomPaymentVerdict.Complete("pay-1")
        val declinedThenPaid = listOf(RevolutPayment("pay-0", "declined", at), RevolutPayment("pay-1", "completed", at))
        verdict(order(payments = declinedThenPaid)) shouldBe CustomPaymentVerdict.Complete("pay-1")
        verdict(order(payments = emptyList())) shouldBe CustomPaymentVerdict.Complete(null)
    }

    @Test
    fun `completed but anything off - NEEDS_REVIEW with the reason, never COMPLETE`() {
        val cases =
            mapOf(
                order(amount = 110_800) to "amount 110800 instead of 110900",
                order(currency = "GBP") to "currency GBP",
                order(outstanding = 100) to "outstanding 100",
                order(reference = "another-token") to "reference does not match",
                order(reference = null) to "reference does not match",
                order(type = "refund") to "type refund",
            )
        cases.forEach { (order, reason) ->
            val result = verdict(order)
            result.shouldBeInstanceOf<CustomPaymentVerdict.NeedsReview>()
            result.reason shouldContain reason
        }
        val all = verdict(order(amount = 1, currency = "USD", reference = "x")) as CustomPaymentVerdict.NeedsReview
        all.reason shouldContain "amount 1 instead of 110900"
        all.reason shouldContain "currency USD"
    }

    @Test
    fun `failed and cancelled end the attempt, every other state is still pending`() {
        verdict(order(state = "failed")) shouldBe CustomPaymentVerdict.Failed
        verdict(order(state = "cancelled")) shouldBe CustomPaymentVerdict.Cancelled
        listOf("pending", "processing", "authorised", "something_new").forEach {
            verdict(order(state = it)) shouldBe CustomPaymentVerdict.StillPending
        }
        // A wrong amount on a failed order is irrelevant: nothing was paid.
        verdict(order(state = "failed", amount = 1)) shouldBe CustomPaymentVerdict.Failed
    }

    @Test
    fun `snapshot keeps the whitelisted fields only - card brand and last four, nothing about the payer`() {
        val method = RevolutPaymentMethod(type = "card", cardBrand = "visa", cardLastFour = "4242")
        val bad = RevolutPaymentMethod(type = "card", cardBrand = "<script>", cardLastFour = "42424242")
        val json =
            OrderSnapshot
                .of(order(payments = listOf(RevolutPayment("pay-1", "completed", at, method), RevolutPayment("pay-2", "declined", null, bad))))
                .toJson()
        json shouldContain "\"card_brand\":\"visa\""
        json shouldContain "\"card_last_four\":\"4242\""
        json shouldContain "\"id\":\"ord-1\""
        json shouldContain "\"outstanding_amount\":0"
        json shouldNotContain "<script>"
        json shouldNotContain "42424242"
        json shouldNotContain token
    }
}
