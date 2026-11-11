package hr.workspace.boat4you.domains.payment.mollie.report

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** What each transfer to the bank consists of, from Mollie's balance transactions (pure). */
class PaymentsReportAssemblerTests {
    private val from: Instant = Instant.parse("2026-10-31T23:00:00Z")
    private val to: Instant = Instant.parse("2026-11-30T23:00:00Z")

    private val party = ReportParty(ReportPaymentKind.BOOKING, "Ana Šimić", "ana@example.com", "1441001/2026")

    @Test
    fun `a transfer consists of everything booked since the transfer before it`() {
        val ledger =
            PaymentsReportAssembler.ledger(
                listOf(
                    // booked in October, after the last October transfer: part of the first November transfer
                    balanceTransaction("payment", "2026-10-30T09:00:00Z", "971.00", "1000.00", "-29.00", paymentContext("tr_oct")),
                    balanceTransaction("payment", "2026-11-02T10:00:00Z", "1011.46", "1030.00", "-18.54", paymentContext("tr_book1")),
                    balanceTransaction("outgoing-transfer", "2026-11-03T05:00:00Z", "-1982.46", context = """{"settlementId": "stl_a", "transferId": "trf_a"}"""),
                    balanceTransaction("payment", "2026-11-05T10:00:00Z", "49.11", "50.00", "-0.89", paymentContext("tr_pay1")),
                    balanceTransaction("refund", "2026-11-06T10:00:00Z", "-50.25", "-50.00", "-0.25", """{"paymentId": "tr_pay1", "refundId": "re_1"}"""),
                    balanceTransaction("movement", "2026-11-06T11:00:00Z", "-0.08"),
                    balanceTransaction("payment", "2026-11-07T10:00:00Z", "500.00", "500.00", null, paymentContext("tr_unknown", "Booking 1441009/2026")),
                    balanceTransaction("outgoing-transfer", "2026-11-10T05:00:00Z", "-498.78", context = """{"settlementId": "stl_b"}"""),
                    balanceTransaction("payment", "2026-11-12T10:00:00Z", "97.10", "100.00", "-2.90", paymentContext("tr_late")),
                    // December: outside the period
                    balanceTransaction("payment", "2026-12-01T10:00:00Z", "10.00", "10.00", null, paymentContext("tr_dec")),
                ),
                from,
                to,
                mapOf("tr_book1" to party),
                complete = true,
            )

        ledger.payouts.map { it.amountMinor } shouldBe listOf(198_246L, 49_878L)
        val first = ledger.payouts[0]
        first.settlementId shouldBe "stl_a"
        first.transferId shouldBe "trf_a"
        first.lines.map { it.molliePaymentId } shouldBe listOf("tr_oct", "tr_book1")
        first.lines[1].party shouldBe party
        first.lines[1].grossMinor shouldBe 103_000L
        first.lines[1].deductionsMinor shouldBe -1_854L
        first.lines[1].netMinor shouldBe 101_146L
        first.differenceMinor shouldBe 0L

        val second = ledger.payouts[1]
        second.lines.map { it.type } shouldBe listOf("payment", "refund", "movement", "payment")
        second.lines[2].grossMinor shouldBe -8L
        second.lines[3].party shouldBe null
        second.lines[3].description shouldBe "Booking 1441009/2026"
        second.linesNetMinor shouldBe 49_878L
        second.differenceMinor shouldBe 0L

        ledger.moneyBack.map { it.netMinor } shouldBe listOf(-5_025L)
        ledger.notPaidOut.map { it.molliePaymentId } shouldBe listOf("tr_late")
    }

    @Test
    fun `a transfer before the period closes its lines but is not listed, and a carried balance shows as a difference`() {
        val ledger =
            PaymentsReportAssembler.ledger(
                listOf(
                    balanceTransaction("payment", "2026-10-20T10:00:00Z", "100.00", "100.00"),
                    balanceTransaction("outgoing-transfer", "2026-10-25T05:00:00Z", "-100.00"),
                    balanceTransaction("refund", "2026-10-26T10:00:00Z", "-10.00", "-10.00"),
                    balanceTransaction("payment", "2026-11-02T10:00:00Z", "60.00", "60.00"),
                    balanceTransaction("outgoing-custom-amount-transfer", "2026-11-03T05:00:00Z", "-40.00"),
                ),
                from,
                to,
                emptyMap(),
                complete = false,
            )

        ledger.payouts.size shouldBe 1
        ledger.payouts[0].amountMinor shouldBe 4_000L
        ledger.payouts[0].linesNetMinor shouldBe 5_000L
        ledger.payouts[0].differenceMinor shouldBe -1_000L
        // the October refund is in the transfer's lines, but was not booked in the period
        ledger.moneyBack shouldBe emptyList()
        ledger.notPaidOut shouldBe emptyList()
        ledger.complete shouldBe false
    }

    @Test
    fun `transactions booked at the same second keep the order they were given in`() {
        val ledger =
            PaymentsReportAssembler.ledger(
                listOf(
                    balanceTransaction("payment", "2026-11-02T10:00:00Z", "10.00", context = paymentContext("tr_a")),
                    balanceTransaction("outgoing-transfer", "2026-11-02T10:00:00Z", "-10.00"),
                    balanceTransaction("payment", "2026-11-02T10:00:00Z", "20.00", context = paymentContext("tr_b")),
                ),
                from,
                to,
                emptyMap(),
                complete = true,
            )

        ledger.payouts[0].lines.map { it.molliePaymentId } shouldBe listOf("tr_a")
        ledger.notPaidOut.map { it.molliePaymentId } shouldBe listOf("tr_b")
    }

    @Test
    fun `Mollie's ids and description are read one level down as well`() {
        val nestedPayment = """{"payment": {"paymentId": "tr_nested1", "paymentDescription": " Boat4You 1441003/2026 "}}"""
        val ledger =
            PaymentsReportAssembler.ledger(
                listOf(
                    balanceTransaction("payment", "2026-11-02T10:00:00Z", "97.10", "100.00", "-2.90", nestedPayment),
                    balanceTransaction("outgoing-transfer", "2026-11-03T05:00:00Z", "-97.10", context = """{"transfer": {"settlementId": "stl_n", "transferId": "trf_n"}}"""),
                    // not ids: left out rather than printed
                    balanceTransaction("outgoing-transfer", "2026-11-04T05:00:00Z", "-1.00", context = """{"settlementId": "stl n <b>", "transferId": 7}"""),
                ),
                from,
                to,
                emptyMap(),
                complete = true,
            )

        ledger.payouts[0].settlementId shouldBe "stl_n"
        ledger.payouts[0].transferId shouldBe "trf_n"
        ledger.payouts[0].lines[0].molliePaymentId shouldBe "tr_nested1"
        ledger.payouts[0].lines[0].description shouldBe "Boat4You 1441003/2026"
        ledger.payouts[1].settlementId shouldBe null
        ledger.payouts[1].transferId shouldBe null
    }
}
