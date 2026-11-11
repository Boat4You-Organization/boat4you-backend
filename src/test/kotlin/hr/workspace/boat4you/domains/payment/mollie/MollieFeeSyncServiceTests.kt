package hr.workspace.boat4you.domains.payment.mollie

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

/** Mollie's fee per payment, read from the balance transactions (mocks; the page bodies follow Mollie's documented shape). */
class MollieFeeSyncServiceTests {
    private val balances: MollieBalanceClient = mock(MollieBalanceClient::class.java)
    private val orders: ReservationCardOrderRepository = mock(ReservationCardOrderRepository::class.java)
    private val customPayments: CustomPaymentRepository = mock(CustomPaymentRepository::class.java)
    private val mapper = jacksonObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val now: Instant = Instant.parse("2026-11-11T12:00:00Z")
    private val since: Instant = now.minus(Duration.ofDays(30))

    private fun service(orgToken: String = "access_notarealmollieorgtoken00000000") =
        MollieFeeSyncService(balances, orders, customPayments, mollieProperties(orgToken = orgToken))

    private fun page(json: String): MollieBalanceTransactionPage = mapper.readValue(json)

    private fun waiting(vararg rows: Pair<String, String>): List<Array<Any?>> = rows.map { arrayOf(it.first, Instant.parse(it.second)) }

    @Test
    fun `without the organisation token nothing is read`() {
        service(orgToken = "").sync(now) shouldBe 0

        verifyNoInteractions(balances, orders, customPayments)
    }

    @Test
    fun `no paid payment waits for its fee - Mollie is not asked`() {
        service().sync(now) shouldBe 0

        verifyNoInteractions(balances)
    }

    @Test
    fun `the fee is the deduction of the payment's own movement, read back to the oldest waiting payment and no further`() {
        `when`(orders.findFeeCandidates(ReservationCardOrderStatus.COMPLETED, since)).thenReturn(waiting("tr_book1" to "2026-11-09T10:00:00Z"))
        `when`(customPayments.findFeeCandidates(CustomPaymentStatus.COMPLETED, since))
            .thenReturn(waiting("tr_pay1" to "2026-11-10T14:21:00Z", "tr_nofee" to "2026-11-10T15:00:00Z"))
        `when`(balances.listTransactions(null)).thenReturn(
            page(
                """
                {"count": 6, "_embedded": {"balance_transactions": [
                  {"resource": "balance-transaction", "id": "baltr_t6", "type": "outgoing-transfer",
                   "resultAmount": {"value": "-900.00", "currency": "EUR"}, "initialAmount": {"value": "-900.00", "currency": "EUR"},
                   "context": {"settlementId": "stl_1", "transferId": "trf_1"}, "createdAt": "2026-11-11T05:00:00+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_t5", "type": "payment",
                   "resultAmount": {"value": "49.11", "currency": "EUR"}, "initialAmount": {"value": "50.00", "currency": "EUR"},
                   "deductions": {"value": "-0.89", "currency": "EUR"},
                   "context": {"paymentId": "tr_pay1", "paymentDescription": "Boat4You"}, "createdAt": "2026-11-10T14:22:36+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_t4", "type": "refund",
                   "resultAmount": {"value": "-50.25", "currency": "EUR"}, "initialAmount": {"value": "-50.00", "currency": "EUR"},
                   "deductions": {"value": "-0.25", "currency": "EUR"},
                   "context": {"paymentId": "tr_pay1", "refundId": "re_1"}, "createdAt": "2026-11-10T14:26:00+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_t3", "type": "payment",
                   "resultAmount": {"value": "99.00", "currency": "EUR"}, "initialAmount": {"value": "100.00", "currency": "EUR"},
                   "deductions": {"value": "-1.00", "currency": "EUR"},
                   "context": {"paymentId": "tr_notours"}, "createdAt": "2026-11-10T13:00:00+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_t2", "type": "payment",
                   "resultAmount": {"value": "70.00", "currency": "EUR"}, "initialAmount": {"value": "70.00", "currency": "EUR"},
                   "deductions": null, "context": {"paymentId": "tr_nofee"}, "createdAt": "2026-11-10T15:01:00+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_t1", "type": "balance-correction",
                   "resultAmount": {"value": "0.01", "currency": "EUR"}, "initialAmount": {"value": "0.01", "currency": "EUR"},
                   "context": [], "createdAt": "2026-11-10T09:00:00+00:00"}
                ]},
                "_links": {"next": {"href": "https://api.mollie.com/v2/balances/bal_x/transactions?from=baltr_page2&limit=250", "type": "application/hal+json"}}}
                """,
            ),
        )
        `when`(balances.listTransactions("baltr_page2")).thenReturn(
            page(
                """
                {"count": 3, "_embedded": {"balance_transactions": [
                  {"resource": "balance-transaction", "id": "baltr_s3", "type": "capture",
                   "resultAmount": {"value": "2834.90", "currency": "EUR"}, "initialAmount": {"value": "2838.00", "currency": "EUR"},
                   "deductions": {"value": "-3.10", "currency": "EUR"},
                   "context": {"capture": {"paymentId": "tr_book1", "captureId": "cpt_1"}}, "createdAt": "2026-11-09T10:05:00+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_s2", "type": "payment",
                   "resultAmount": {"value": "-0.40", "currency": "EUR"}, "initialAmount": {"value": "0.00", "currency": "EUR"},
                   "deductions": {"value": "-0.40", "currency": "EUR"},
                   "context": {"paymentId": "tr_book1"}, "createdAt": "2026-11-09T10:04:00+00:00"},
                  {"resource": "balance-transaction", "id": "baltr_s1", "type": "payment",
                   "resultAmount": {"value": "10.00", "currency": "EUR"}, "initialAmount": {"value": "10.29", "currency": "EUR"},
                   "deductions": {"value": "-0.29", "currency": "EUR"},
                   "context": {"paymentId": "tr_old"}, "createdAt": "2026-11-01T08:00:00+00:00"}
                ]},
                "_links": {"next": {"href": "https://api.mollie.com/v2/balances/bal_x/transactions?from=baltr_page3&limit=250", "type": "application/hal+json"}}}
                """,
            ),
        )
        `when`(orders.storeProviderFee("tr_book1", 350)).thenReturn(1)
        `when`(customPayments.storeProviderFee("tr_pay1", 89)).thenReturn(1)

        service().sync(now) shouldBe 2

        // The refund's own deduction is not the payment's fee; a movement without deductions stores nothing.
        verify(customPayments).storeProviderFee("tr_pay1", 89)
        verify(orders).storeProviderFee("tr_book1", 350)
        verify(customPayments, never()).storeProviderFee("tr_nofee", 0)
        verify(orders, never()).storeProviderFee("tr_notours", 100)
        verify(customPayments, never()).storeProviderFee("tr_notours", 100)
        // Page 2 ends before the oldest waiting payment was created: page 3 is never asked for.
        verify(balances, never()).listTransactions("baltr_page3")
    }

    @Test
    fun `the cursor is only ever a well-formed transaction id taken from Mollie's next link`() {
        MollieBalanceClient.cursorOf("https://api.mollie.com/v2/balances/bal_x/transactions?from=baltr_QM24QwzUWR4ev4Xfgyt29d&limit=250") shouldBe "baltr_QM24QwzUWR4ev4Xfgyt29d"
        MollieBalanceClient.cursorOf("https://api.mollie.com/v2/balances/bal_x/transactions?limit=250") shouldBe null
        MollieBalanceClient.cursorOf("https://api.mollie.com/v2/balances/bal_x/transactions?from=../../v2/payments") shouldBe null
        MollieBalanceClient.cursorOf("not a url") shouldBe null
        MollieBalanceClient.cursorOf(null) shouldBe null
    }
}
