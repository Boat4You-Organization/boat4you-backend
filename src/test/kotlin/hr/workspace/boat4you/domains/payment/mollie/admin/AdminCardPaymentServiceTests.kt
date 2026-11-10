package hr.workspace.boat4you.domains.payment.mollie.admin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.payment.mollie.MollieClient
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingPaymentService
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.payment.mollie.mollieProperties
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentNotFoundException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.web.client.ResourceAccessException
import java.math.BigDecimal

/** Admin view of the Mollie payments over mocks: what a row shows, and which payments the live details may read. */
class AdminCardPaymentServiceTests {
    private val orders: ReservationCardOrderRepository = mock(ReservationCardOrderRepository::class.java)
    private val customPayments: CustomPaymentRepository = mock(CustomPaymentRepository::class.java)
    private val bookingPayments: MollieBookingPaymentService = mock(MollieBookingPaymentService::class.java)
    private val client: MollieClient = mock(MollieClient::class.java)

    private fun service(apiKey: String = "live_notarealmollieapikey0000000000") =
        AdminCardPaymentService(orders, customPayments, bookingPayments, client, mollieProperties(apiKey = apiKey))

    private val mapper = jacksonObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Trimmed copy of a real `GET /v2/payments/{id}?embed=refunds,chargebacks` answer (ids and card data made up). */
    private val paidWithRefund: MolliePayment =
        mapper.readValue(
            """
            {
              "resource": "payment", "id": "tr_7UhSN1zuXS", "mode": "live", "status": "paid", "method": "creditcard",
              "createdAt": "2026-11-10T14:21:25+00:00", "paidAt": "2026-11-10T14:22:36+00:00",
              "amount": {"value": "50.00", "currency": "EUR"},
              "amountRefunded": {"value": "20.00", "currency": "EUR"},
              "amountRemaining": {"value": "30.00", "currency": "EUR"},
              "settlementAmount": {"value": "50.00", "currency": "EUR"},
              "description": "Boat4You - contract testing",
              "details": {
                "cardHolder": "NOT KEPT", "cardNumber": "9996", "cardLabel": "Visa", "cardCountryCode": "HR",
                "cardFunding": "credit", "cardAudience": "business", "feeRegion": "intra-eu-corporate", "cardSecurity": "3dsecure"
              },
              "_embedded": {
                "refunds": [
                  {"resource": "refund", "id": "re_4qqhO89gsT", "amount": {"value": "20.00", "currency": "EUR"},
                   "status": "pending", "createdAt": "2026-11-10T14:26:51+00:00", "description": "Test refund", "paymentId": "tr_7UhSN1zuXS"}
                ],
                "chargebacks": [
                  {"resource": "chargeback", "id": "chb_n9z0tp", "amount": {"value": "30.00", "currency": "EUR"},
                   "createdAt": "2026-12-01T09:00:00+00:00", "reason": {"code": "AC01", "description": "Account identifier incorrect"}}
                ]
              },
              "_links": {"self": {"href": "https://api.mollie.com/v2/payments/tr_7UhSN1zuXS", "type": "application/hal+json"}}
            }
            """.trimIndent(),
        )

    @Test
    fun `details - a booking payment as Mollie reports it, refunds and chargebacks in cents`() {
        `when`(orders.existsByProviderOrderId("tr_7UhSN1zuXS")).thenReturn(true)
        `when`(client.getPaymentWithMoneyBack("tr_7UhSN1zuXS")).thenReturn(paidWithRefund)
        with(service().mollieDetails("tr_7UhSN1zuXS")) {
            status shouldBe "paid"
            mode shouldBe "live"
            method shouldBe "creditcard"
            amountMinor shouldBe 5_000L
            refundedAmountMinor shouldBe 2_000L
            chargedBackAmountMinor shouldBe 0L
            cardLabel shouldBe "Visa"
            cardLast4 shouldBe "9996"
            cardFunding shouldBe "credit"
            cardAudience shouldBe "business"
            feeRegion shouldBe "intra-eu-corporate"
            refunds shouldHaveSize 1
            refunds[0].id shouldBe "re_4qqhO89gsT"
            refunds[0].amountMinor shouldBe 2_000L
            refunds[0].status shouldBe "pending"
            chargebacks shouldHaveSize 1
            chargebacks[0].amountMinor shouldBe 3_000L
            chargebacks[0].reason shouldBe "Account identifier incorrect"
        }
    }

    @Test
    fun `details - a pay-link payment is ours too, anything else is a 404 and Mollie is never asked`() {
        `when`(customPayments.existsByProviderOrderId("tr_customPay1")).thenReturn(true)
        `when`(client.getPaymentWithMoneyBack("tr_customPay1")).thenReturn(paidWithRefund.copy(id = "tr_customPay1", embedded = null, details = null))
        with(service().mollieDetails("tr_customPay1")) {
            refunds shouldHaveSize 0
            chargebacks shouldHaveSize 0
            cardLast4 shouldBe null
        }

        shouldThrow<CardPaymentNotFoundException> { service().mollieDetails("tr_someoneElses") }
        shouldThrow<CardPaymentNotFoundException> { service().mollieDetails("tr_bad/../id") }
        verify(client, never()).getPaymentWithMoneyBack("tr_someoneElses")
    }

    @Test
    fun `details - Mollie not configured is a 503, Mollie failing or not knowing the payment a 502`() {
        `when`(orders.existsByProviderOrderId("tr_7UhSN1zuXS")).thenReturn(true)
        shouldThrow<CardPaymentsUnavailableException> { service(apiKey = "").mollieDetails("tr_7UhSN1zuXS") }
        verifyNoInteractions(client)

        `when`(client.getPaymentWithMoneyBack("tr_7UhSN1zuXS")).thenThrow(ResourceAccessException("timeout"))
        shouldThrow<CardPaymentProviderException> { service().mollieDetails("tr_7UhSN1zuXS") }

        `when`(orders.existsByProviderOrderId("tr_gone")).thenReturn(true)
        `when`(client.getPaymentWithMoneyBack("tr_gone")).thenReturn(null)
        shouldThrow<CardPaymentProviderException> { service().mollieDetails("tr_gone") }
    }

    @Test
    fun `refresh - applies Mollie's state first, then returns the row with the client and booking number`() {
        val order =
            ReservationCardOrder().apply {
                id = 5
                providerOrderId = "tr_7UhSN1zuXS"
                reservationId = 42
                reservationFlowId = 900
                phaseIds = "7,8"
                payFullAmount = true
                baseAmount = BigDecimal("2677.60")
                surchargeAmount = BigDecimal("133.88")
                amountMinor = 281_148
                reference = "1441002/2026"
                status = ReservationCardOrderStatus.COMPLETED
            }
        `when`(orders.findAdminRowById(5)).thenReturn(listOf(arrayOf(order, " Ana ", "Kovac", "ana@example.com", " ", "1441002/2026")))
        with(service().refresh(5)) {
            status shouldBe ReservationCardOrderStatus.COMPLETED
            clientName shouldBe "Ana Kovac"
            clientEmail shouldBe "ana@example.com"
            clientPhone shouldBe null
            reservationNumber shouldBe "1441002/2026"
            baseAmountMinor shouldBe 267_760L
            surchargeAmountMinor shouldBe 13_388L
            amountMinor shouldBe 281_148L
            phaseIds shouldBe listOf(7L, 8L)
            payFullAmount shouldBe true
        }
        verify(bookingPayments).refresh(5)

        `when`(orders.findAdminRowById(6)).thenReturn(emptyList())
        shouldThrow<CardPaymentNotFoundException> { service().refresh(6) }
    }
}
