package hr.workspace.boat4you.domains.payment.mollie.report

import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceClient
import hr.workspace.boat4you.domains.payment.mollie.MollieBalancePageLinks
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceTransaction
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceTransactionPage
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceTransactionsEmbedded
import hr.workspace.boat4you.domains.payment.mollie.MollieLink
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.payment.mollie.mollieProperties
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** The accounting report's data: our ledgers for who paid, Mollie's balance for refunds and transfers (mocks). */
class PaymentsReportServiceTests {
    private val orders: ReservationCardOrderRepository = mock(ReservationCardOrderRepository::class.java)
    private val customPayments: CustomPaymentRepository = mock(CustomPaymentRepository::class.java)
    private val reservations: ReservationRepository = mock(ReservationRepository::class.java)
    private val balances: MollieBalanceClient = mock(MollieBalanceClient::class.java)

    private val from: LocalDate = LocalDate.of(2026, 11, 1)
    private val to: LocalDate = LocalDate.of(2026, 11, 30)

    // Croatian days: 1.11. 00:00 CET = 31.10. 23:00 UTC; the end is the first instant of 1.12.
    private val start: Instant = Instant.parse("2026-10-31T23:00:00Z")
    private val end: Instant = Instant.parse("2026-11-30T23:00:00Z")

    private fun service(orgToken: String = "access_notarealmollieorgtoken00000000") =
        PaymentsReportService(orders, customPayments, reservations, balances, mollieProperties(orgToken = orgToken))

    private fun order(
        paymentId: String,
        completedAt: String?,
    ) = ReservationCardOrder().apply {
        providerOrderId = paymentId
        reference = "1441001-2026"
        baseAmount = BigDecimal("1000.00")
        surchargeAmount = BigDecimal("30.00")
        amountMinor = 103_000
        providerFeeMinor = 1_854
        refundedAmountMinor = 5_000
        chargedBackAmountMinor = 1_000
        status = ReservationCardOrderStatus.COMPLETED
        this.completedAt = completedAt?.let { Instant.parse(it) }
    }

    private fun linkPayment(
        paymentId: String?,
        completedAt: String?,
        reservationId: Long? = null,
    ) = CustomPayment().apply {
        providerOrderId = paymentId
        reference = "Charter Split 2027"
        amountMinor = 5_000
        payerEmail = "payer@example.com"
        method = CustomPayment.METHOD_KLARNA
        status = CustomPaymentStatus.COMPLETED
        this.reservationId = reservationId
        this.completedAt = completedAt?.let { Instant.parse(it) }
    }

    private fun page(
        transactions: List<MollieBalanceTransaction>,
        next: String? = null,
    ) = MollieBalanceTransactionPage(
        MollieBalanceTransactionsEmbedded(transactions),
        MollieBalancePageLinks(next?.let { MollieLink("https://api.mollie.com/v2/balances/primary/transactions?from=$it&limit=250") }),
    )

    @Test
    fun `paid payments of both ledgers, by time of payment, with payer and booking number`() {
        `when`(orders.findReportRowsPaidBetween(ReservationCardOrderStatus.COMPLETED, start, end)).thenReturn(
            listOf(
                arrayOf(order("tr_book1", "2026-11-05T10:00:00Z"), " Ana ", "Šimić", "ana@example.com", "1441001/2026"),
                arrayOf(order("tr_nodate", null), "No", "Date", null, null),
            ),
        )
        `when`(customPayments.findPaidBetween(CustomPaymentStatus.COMPLETED, start, end)).thenReturn(
            listOf(linkPayment("tr_pay1", "2026-11-02T08:00:00Z", reservationId = 77), linkPayment(null, "2026-11-03T08:00:00Z")),
        )
        `when`(reservations.findReportPartiesByIdIn(listOf(77L))).thenReturn(listOf(arrayOf(77L, "1441007/2026", "Ivo", "Đurić")))

        val report = service(orgToken = "").build(from, to)

        report.ledger shouldBe null
        report.payments.map { it.molliePaymentId } shouldBe listOf("tr_pay1", "tr_book1")

        val link = report.payments[0]
        link.party shouldBe ReportParty(ReportPaymentKind.LINK, "Ivo Đurić", "payer@example.com", "Charter Split 2027", "1441007/2026")
        link.method shouldBe "KLARNA"
        link.amountMinor shouldBe 5_000L
        link.feeMinor shouldBe null
        link.baseMinor shouldBe null

        val booking = report.payments[1]
        booking.party shouldBe ReportParty(ReportPaymentKind.BOOKING, "Ana Šimić", "ana@example.com", "1441001/2026")
        booking.method shouldBe "CARD"
        booking.baseMinor shouldBe 100_000L
        booking.surchargeMinor shouldBe 3_000L
        booking.feeMinor shouldBe 1_854L
        booking.moneyBackMinor shouldBe 6_000L
        verifyNoInteractions(balances)
    }

    @Test
    fun `the balance is read back to the last transfer before the period and no further`() {
        `when`(balances.listTransactions(null)).thenReturn(
            page(
                listOf(
                    balanceTransaction("payment", "2026-12-02T10:00:00Z", "10.00", context = paymentContext("tr_dec")),
                    balanceTransaction("outgoing-transfer", "2026-11-10T05:00:00Z", "-148.11", context = """{"settlementId": "stl_b"}"""),
                ),
                next = "baltr_p2",
            ),
        )
        `when`(balances.listTransactions("baltr_p2")).thenReturn(
            page(
                listOf(
                    balanceTransaction("payment", "2026-11-05T10:00:00Z", "49.11", "50.00", "-0.89", paymentContext("tr_pay1")),
                    balanceTransaction("payment", "2026-10-30T10:00:00Z", "99.00", "100.00", "-1.00", paymentContext("tr_book1")),
                    balanceTransaction("outgoing-transfer", "2026-10-28T05:00:00Z", "-500.00"),
                    balanceTransaction("payment", "2026-10-27T10:00:00Z", "500.00", context = paymentContext("tr_old")),
                ),
                next = "baltr_p3",
            ),
        )
        `when`(orders.findReportRowsByProviderOrderIdIn(listOf("tr_book1", "tr_pay1", "tr_dec")))
            .thenReturn(listOf(arrayOf(order("tr_book1", "2026-10-30T09:59:00Z"), "Ana", "Šimić", "ana@example.com", null)))
        `when`(customPayments.findAllByProviderOrderIdIn(listOf("tr_book1", "tr_pay1", "tr_dec"))).thenReturn(listOf(linkPayment("tr_pay1", "2026-11-05T09:59:00Z")))

        val ledger = service().build(from, to).ledger!!

        verify(balances, never()).listTransactions("baltr_p3")
        ledger.complete shouldBe true
        ledger.payouts.size shouldBe 1
        val payout = ledger.payouts[0]
        payout.amountMinor shouldBe 14_811L
        payout.lines.map { it.molliePaymentId } shouldBe listOf("tr_book1", "tr_pay1")
        // without a reservation number the booking number sent to Mollie stands in
        payout.lines[0].party shouldBe ReportParty(ReportPaymentKind.BOOKING, "Ana Šimić", "ana@example.com", "1441001-2026")
        payout.lines[1].party shouldBe ReportParty(ReportPaymentKind.LINK, null, "payer@example.com", "Charter Split 2027")
        payout.differenceMinor shouldBe 0L
        ledger.notPaidOut shouldBe emptyList()
    }

    @Test
    fun `when Mollie does not answer the report still lists the payments`() {
        `when`(balances.listTransactions(null)).thenThrow(IllegalStateException("boom"))

        val report = service().build(from, to)

        report.ledger shouldBe null
        report.payments shouldBe emptyList()
    }

    @Test
    fun `a balance with no transfer at all is read to its start`() {
        `when`(balances.listTransactions(null)).thenReturn(
            page(listOf(balanceTransaction("refund", "2026-11-10T12:01:00Z", "-50.25", "-50.00", "-0.25", """{"paymentId": "tr_pay1", "refundId": "re_1"}"""))),
        )

        val ledger = service().build(from, to).ledger!!

        ledger.payouts shouldBe emptyList()
        ledger.moneyBack.map { it.netMinor } shouldBe listOf(-5_025L)
        ledger.notPaidOut.size shouldBe 1
    }
}
