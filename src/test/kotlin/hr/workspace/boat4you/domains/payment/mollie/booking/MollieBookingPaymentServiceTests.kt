package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.payment.mollie.MollieClient
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.payment.mollie.mollieProperties
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.service.StripeSessionExpirer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.UUID

/** checkStatus of the Mollie booking provider over mocks: which ledger statuses ask Mollie and what the payer is told. */
class MollieBookingPaymentServiceTests {
    private val orders: ReservationCardOrderRepository = mock(ReservationCardOrderRepository::class.java)
    private val client: MollieClient = mock(MollieClient::class.java)
    private val orderTx: ReservationCardOrderTx = mock(ReservationCardOrderTx::class.java)
    private val followUp: BookingCardCompletionFollowUp = mock(BookingCardCompletionFollowUp::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private fun service(enabledKey: String = "live_notarealmollieapikey0000000000") =
        MollieBookingPaymentService(
            mollieProperties(apiKey = enabledKey),
            client,
            mock(MollieBookingCreateTx::class.java),
            orderTx,
            orders,
            followUp,
            mock(StripeSessionExpirer::class.java),
            mock(BookingCardAlertMailer::class.java),
            "https://www.boat4you.com",
        )

    private val token: UUID = UUID.fromString("0b6f7c1e-3c55-4d1e-9a7e-1f2d3c4b5a69")

    private fun row(status: ReservationCardOrderStatus) =
        ReservationCardOrder().apply {
            id = 5
            providerOrderId = "tr_7UhSN1zuXS"
            checkoutToken = token
            reservationId = 42
            reservationFlowId = 900
            phaseIds = "7"
            paymentPhaseId = 7
            amountMinor = 281_160
            this.status = status
        }

    private fun payment(status: String) =
        MolliePayment(
            id = "tr_7UhSN1zuXS",
            status = status,
            amount = MollieAmount.eur(281_160),
            metadata = mapOf("reservationFlowId" to "900", "phaseIds" to "7"),
        )

    private fun status(ref: String = token.toString()) = service().checkStatus(ref)!!.status

    private fun givenRow(row: ReservationCardOrder) {
        `when`(orders.findByCheckoutToken(token)).thenReturn(row)
        `when`(orders.findByProviderOrderId("tr_7UhSN1zuXS")).thenReturn(row)
        `when`(orders.findById(5L)).thenAnswer { Optional.of(row) }
        `when`(orders.claimCheck(5)).thenReturn(1)
    }

    @Test
    fun `a SUPERSEDED payment that Mollie reports paid is recorded and the payer sees success`() {
        val row = row(ReservationCardOrderStatus.SUPERSEDED)
        givenRow(row)
        `when`(client.getPaymentOnce("tr_7UhSN1zuXS")).thenReturn(payment("paid"))
        `when`(orderTx.applyCompleted(ArgumentMatchers.eq(5L), any(), ArgumentMatchers.isNull())).thenAnswer {
            row.status = ReservationCardOrderStatus.COMPLETED
            CompletionResult(completedNow = true, promote = false, reservationId = 42)
        }
        status() shouldBe CheckoutSessionStatusEnum.PAYMENT_SUCCESS
        verify(orderTx).applyCompleted(ArgumentMatchers.eq(5L), any(), ArgumentMatchers.isNull())
        verify(followUp).afterCompletion(ArgumentMatchers.eq(5L), any())
        verify(orderTx).applyMoneyBack(ArgumentMatchers.eq(5L), any())
        // Same answer when the web asks by the Mollie id.
        status("tr_7UhSN1zuXS") shouldBe CheckoutSessionStatusEnum.PAYMENT_SUCCESS
    }

    @Test
    fun `open and still-processing payments - SUPERSEDED or PENDING - are asked from Mollie, open means not completed`() {
        listOf(ReservationCardOrderStatus.PENDING, ReservationCardOrderStatus.SUPERSEDED).forEach { stored ->
            givenRow(row(stored))
            `when`(client.getPaymentOnce("tr_7UhSN1zuXS")).thenReturn(payment("open"))
            status() shouldBe CheckoutSessionStatusEnum.PAYMENT_NOT_COMPLETED
            `when`(client.getPaymentOnce("tr_7UhSN1zuXS")).thenReturn(payment("pending"))
            status() shouldBe CheckoutSessionStatusEnum.PAYMENT_PENDING
        }
    }

    @Test
    fun `final rows are never asked from Mollie - COMPLETED success, FAILED and CANCELLED not completed, NEEDS_REVIEW pending`() {
        mapOf(
            ReservationCardOrderStatus.COMPLETED to CheckoutSessionStatusEnum.PAYMENT_SUCCESS,
            ReservationCardOrderStatus.FAILED to CheckoutSessionStatusEnum.PAYMENT_NOT_COMPLETED,
            ReservationCardOrderStatus.CANCELLED to CheckoutSessionStatusEnum.PAYMENT_NOT_COMPLETED,
            ReservationCardOrderStatus.NEEDS_REVIEW to CheckoutSessionStatusEnum.PAYMENT_PENDING,
        ).forEach { (stored, shown) ->
            givenRow(row(stored))
            status() shouldBe shown
        }
        verify(client, never()).getPaymentOnce(ArgumentMatchers.anyString())
        verify(orders, never()).claimCheck(ArgumentMatchers.anyLong())
    }

    @Test
    fun `unknown reference or a recently checked SUPERSEDED row - null, respectively the stored answer without a Mollie call`() {
        service().checkStatus("not-a-token-or-payment-id") shouldBe null
        givenRow(row(ReservationCardOrderStatus.SUPERSEDED))
        `when`(orders.claimCheck(5)).thenReturn(0)
        status() shouldBe CheckoutSessionStatusEnum.PAYMENT_PENDING
        verify(client, never()).getPaymentOnce(ArgumentMatchers.anyString())
    }
}

