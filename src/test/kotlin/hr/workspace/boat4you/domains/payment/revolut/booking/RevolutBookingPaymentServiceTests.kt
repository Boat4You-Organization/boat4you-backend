package hr.workspace.boat4you.domains.payment.revolut.booking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.common.errorhandling.ApiErrorCodes
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.payment.revolut.RevolutCreateOrderRequest
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantClient
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantOrderData
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutPayment
import hr.workspace.boat4you.domains.payment.revolut.RevolutProperties
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.payment.revolut.revolutProperties
import hr.workspace.boat4you.domains.reservation.controllers.PublicCardPaymentController
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.StripeSessionExpirer
import hr.workspace.boat4you.domains.reservation.service.payment.CardAlreadyPaidException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentInProgressException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentRouter
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/**
 * Revolut booking checkout (plan 2A.4, 2A.10 M1-M3) over the real RevolutBookingCreateTx / ReservationCardOrderTx
 * logic with mocked repositories, Stripe and Revolut: the order body, reuse before create, the stale-order cleanup and
 * its 409s, 502 without a ledger row, the M1 Stripe expiry, the M3 lifetime and the status endpoint. The 409 / 502
 * bodies are checked through the real controller + ApiErrorHandler.
 */
class RevolutBookingPaymentServiceTests {
    private val reservations: ReservationRepository = mock(ReservationRepository::class.java)
    private val phases: ReservationPaymentPhaseRepository = mock(ReservationPaymentPhaseRepository::class.java)
    private val orders: ReservationCardOrderRepository = mock(ReservationCardOrderRepository::class.java)
    private val settings: AdminSettingsService = mock(AdminSettingsService::class.java)
    private val stripeSessions: StripeSessionExpirer = mock(StripeSessionExpirer::class.java)
    private val client: RevolutMerchantClient = mock(RevolutMerchantClient::class.java)
    private val completion: BookingOrderCompletion = mock(BookingOrderCompletion::class.java)
    private val bookingProperties = RevolutBookingProperties(Duration.ofHours(1), "automatic")
    private val createTx = RevolutBookingCreateTx(reservations, phases, orders, settings, stripeSessions, bookingProperties)
    private val orderTx = ReservationCardOrderTx(orders)

    private val today: LocalDate = LocalDate.now()
    private val checkoutUrl = "https://checkout.revolut.com/payment-link/ord-new"

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    /** `eq` that hands Kotlin the real value (Mockito's returns null / 0 for non-primitives). */
    private fun <T> eq(value: T): T = ArgumentMatchers.eq(value) ?: value

    @Suppress("UNCHECKED_CAST")
    private fun <T> capture(captor: ArgumentCaptor<T>): T = captor.capture() ?: null as T

    private fun service(
        properties: RevolutProperties = revolutProperties(),
        booking: RevolutBookingProperties = bookingProperties,
    ) = RevolutBookingPaymentService(properties, booking, client, createTx, orderTx, orders, completion, "https://www.boat4you.com/")

    private fun phase(
        id: Long,
        amount: String,
        dueInDays: Long,
        paid: Boolean = false,
        stripeSession: String? = null,
    ) = ReservationPaymentPhase().apply {
        this.id = id
        this.amount = BigDecimal(amount)
        deadline = today.plusDays(dueInDays)
        paidOn = if (paid) Instant.now().minusSeconds(3600) else null
        stripeSessionId = stripeSession
    }

    private fun reservation(
        vararg paymentPhases: ReservationPaymentPhase,
        number: String? = "1441002/2026",
        sysStatus: ReservationStatus = ReservationStatus.RESERVATION,
        optionExpiresAt: LocalDateTime? = null,
    ): Reservation {
        val flow =
            ReservationFlow().apply {
                id = FLOW_ID
                this.paymentPhases = paymentPhases.toMutableSet()
                calculatedTotalPrice = BigDecimal("5000.00")
            }
        paymentPhases.forEach { it.reservationFlow = flow }
        val reservation =
            Reservation().apply {
                id = RESERVATION_ID
                reservationNumber = number
                this.sysStatus = sysStatus
                this.optionExpiresAt = optionExpiresAt
                reservationFlow = flow
            }
        `when`(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation))
        return reservation
    }

    private fun revolutOrder(
        id: String,
        state: String,
        amount: Long = 281_160,
        checkout: String? = "https://checkout.revolut.com/payment-link/$id",
    ) = RevolutOrder(
        id = id,
        type = "payment",
        state = state,
        amount = amount,
        currency = "EUR",
        outstandingAmount = 0,
        checkoutUrl = checkout,
        merchantOrderData = RevolutMerchantOrderData("1441002/2026"),
        payments = listOf(RevolutPayment("pay-$id", state)),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun ledgerRow(
        id: Long,
        orderId: String,
        status: ReservationCardOrderStatus = ReservationCardOrderStatus.PENDING,
        phaseIds: String = "7",
        amountMinor: Long = 281_160,
    ) = ReservationCardOrder().apply {
        this.id = id
        providerOrderId = orderId
        reservationId = RESERVATION_ID
        reservationFlowId = FLOW_ID
        this.phaseIds = phaseIds
        paymentPhaseId = 7
        baseAmount = BigDecimal("2677.60")
        surchargeAmount = BigDecimal("134")
        this.amountMinor = amountMinor
        reference = "1441002/2026"
        checkoutUrl = "https://checkout.revolut.com/payment-link/$orderId"
        this.status = status
        expiresAt = Instant.now().plus(Duration.ofMinutes(50))
    }

    /** createOrder answers with a fresh order; saveAndFlush hands the row back with an id. Returns both captors. */
    private fun acceptCreates(order: RevolutOrder = revolutOrder("ord-new", "pending", checkout = checkoutUrl)): Pair<ArgumentCaptor<RevolutCreateOrderRequest>, ArgumentCaptor<ReservationCardOrder>> {
        // doReturn / doAnswer: re-stubbing in a loop must not call the previous stub.
        doReturn(order).`when`(client).createOrder(any())
        doAnswer { invocation -> (invocation.arguments[0] as ReservationCardOrder).apply { id = 501 } }
            .`when`(orders)
            .saveAndFlush(any<ReservationCardOrder>())
        return ArgumentCaptor.forClass(RevolutCreateOrderRequest::class.java) to ArgumentCaptor.forClass(ReservationCardOrder::class.java)
    }

    private fun capturedRequest(captor: ArgumentCaptor<RevolutCreateOrderRequest>): RevolutCreateOrderRequest {
        verify(client).createOrder(capture(captor))
        return captor.value
    }

    private fun capturedRow(captor: ArgumentCaptor<ReservationCardOrder>): ReservationCardOrder {
        verify(orders).saveAndFlush(capture(captor))
        return captor.value
    }

    private fun mvc(service: RevolutBookingPaymentService) =
        MockMvcBuilders
            .standaloneSetup(PublicCardPaymentController(CardPaymentRouter(listOf(service), revolutProperties(), bookingProperties, "revolut")))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    private fun expectCheckoutError(
        service: RevolutBookingPaymentService,
        httpStatus: Int,
        code: ApiErrorCodes,
    ) {
        mvc(service).post("/public/payments/card/create-checkout-session/$RESERVATION_ID?paymentPhaseId=7&idempotencyKey=key-1&locale=de").andExpect {
            status { isEqualTo(httpStatus) }
            jsonPath("$.code") { value(code.code) }
            jsonPath("$.message") { value(code.message) }
        }
    }

    @BeforeEach
    fun surcharge() {
        `when`(settings.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE)).thenReturn(SettingsDto(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE, "5"))
    }

    @Test
    fun `create - the order body (amount = base + surcharge in cents, EUR, automatic capture, reference, metadata, localised redirect, TTL, challenge, no customer) and the frozen ledger row`() {
        reservation(phase(7, "2677.60", 10), phase(8, "1000.00", 40))
        val (requestCaptor, rowCaptor) = acceptCreates()

        val dto = service().createCheckout(RESERVATION_ID, null, 7, " key-1 ", "de")

        dto shouldBe CheckoutSessionDto("ord-new", CheckoutSessionStatusEnum.PAYMENT_PENDING, checkoutUrl)
        val json = jacksonObjectMapper().valueToTree<JsonNode>(capturedRequest(requestCaptor))
        json.fieldNames().asSequence().toList() shouldContainExactly
            listOf("amount", "currency", "capture_mode", "description", "redirect_url", "merchant_order_data", "metadata", "expire_pending_after", "enforce_challenge")
        json["amount"].asLong() shouldBe 281_160L // 2677.60 + 5 % (133.88 -> 134 whole euros) = 2811.60: the 1441002 golden value
        json["currency"].asText() shouldBe "EUR"
        json["capture_mode"].asText() shouldBe "automatic"
        json["description"].asText() shouldBe "Boat4you reservation #1441002/2026"
        json["redirect_url"].asText() shouldBe "https://www.boat4you.com/de/payment-success?reservationId=42"
        json["merchant_order_data"]["reference"].asText() shouldBe "1441002/2026"
        json["metadata"].toString() shouldBe """{"reservationId":"42","reservationFlowId":"900","paymentPhaseId":"7"}"""
        json["expire_pending_after"].asText() shouldBe "PT1H"
        json["enforce_challenge"].asText() shouldBe "automatic"
        json.has("customer") shouldBe false

        with(capturedRow(rowCaptor)) {
            providerOrderId shouldBe "ord-new"
            provider shouldBe "REVOLUT"
            reservationId shouldBe RESERVATION_ID
            reservationFlowId shouldBe FLOW_ID
            phaseIds shouldBe "7"
            payFullAmount.shouldBeNull()
            paymentPhaseId shouldBe 7L
            baseAmount shouldBe BigDecimal("2677.60")
            surchargeAmount shouldBe BigDecimal("134")
            amountMinor shouldBe 281_160L
            currency shouldBe "EUR"
            reference shouldBe "1441002/2026"
            idempotencyKey shouldBe "key-1"
            this.checkoutUrl shouldBe this@RevolutBookingPaymentServiceTests.checkoutUrl
            status shouldBe ReservationCardOrderStatus.PENDING
            Duration.between(Instant.now(), expiresAt).toMinutes() shouldBe 59L
        }
        // The create transaction and the ledger insert both serialise on the reservation flow.
        verify(orders, times(2)).lockKey("reservation-card-order|$FLOW_ID")
        verify(phases).findAllByIdIn(listOf(7L))
    }

    @Test
    fun `create - pay full charges every unpaid phase (ids sorted), payFullAmount in the metadata, no payment phase on the row`() {
        reservation(phase(8, "1000.00", 40), phase(7, "2677.60", 10), phase(6, "500.00", -20, paid = true))
        val (requestCaptor, rowCaptor) = acceptCreates()

        service().createCheckout(RESERVATION_ID, true, null, null, "en")

        with(capturedRequest(requestCaptor)) {
            amount shouldBe 386_160L // 3677.60 + 184 (183.88 rounded on the sum, as Stripe)
            metadata shouldBe mapOf("reservationId" to "42", "reservationFlowId" to "900", "payFullAmount" to "true")
            redirectUrl shouldBe "https://www.boat4you.com/payment-success?reservationId=42"
        }
        with(capturedRow(rowCaptor)) {
            phaseIds shouldBe "7,8"
            payFullAmount shouldBe true
            paymentPhaseId.shouldBeNull()
            idempotencyKey.shouldBeNull()
        }
    }

    @Test
    fun `redirect - English, junk, region tags and blanks have no prefix, the other web languages theirs`() {
        reservation(phase(7, "2677.60", 10))
        val (requestCaptor, _) = acceptCreates()
        val expected =
            linkedMapOf(
                null to "",
                "en" to "",
                "EN" to "",
                " " to "",
                "xx" to "",
                "de-DE" to "",
                "../evil" to "",
                "de" to "/de",
                "HR" to "/hr",
                " nl " to "/nl",
                "pl" to "/pl",
                "it" to "/it",
            )
        expected.forEach { (locale, prefix) ->
            clearInvocations(client)
            service().createCheckout(RESERVATION_ID, null, 7, null, locale)
            capturedRequest(requestCaptor).redirectUrl shouldBe "https://www.boat4you.com$prefix/payment-success?reservationId=42"
        }
    }

    @Test
    fun `reference - the reservation number sanitised as Stripe does, the id when there is no number`() {
        RevolutBookingCreateTx.reference("1441 002/2026<script>;", 42) shouldBe "1441002/2026script"
        RevolutBookingCreateTx.reference(null, 42) shouldBe "42"
        RevolutBookingCreateTx.reference(" ;:", 42) shouldBe "42"

        reservation(phase(7, "2677.60", 10), number = "1441 002/2026<b>")
        val (requestCaptor, rowCaptor) = acceptCreates()
        service().createCheckout(RESERVATION_ID, null, 7, null, "en")
        with(capturedRequest(requestCaptor)) {
            description shouldBe "Boat4you reservation #1441002/2026b"
            merchantOrderData shouldBe RevolutMerchantOrderData("1441002/2026b")
        }
        capturedRow(rowCaptor).reference shouldBe "1441002/2026b"
    }

    @Test
    fun `reuse hit - same flow, phases, amount and payment phase - the stored checkout again, no Revolut call, no new row`() {
        reservation(phase(7, "2677.60", 10))
        val existing = ledgerRow(77, "ord-open")
        `when`(orders.findReusable(eq(FLOW_ID), eq("7"), eq(281_160L), eq(7L), any())).thenReturn(existing)

        val dto = service().createCheckout(RESERVATION_ID, null, 7, "another-key", "de")

        dto shouldBe CheckoutSessionDto("ord-open", CheckoutSessionStatusEnum.PAYMENT_PENDING, "https://checkout.revolut.com/payment-link/ord-open")
        verifyNoInteractions(client)
        verify(orders, never()).saveAndFlush(any<ReservationCardOrder>())
        // The reuse window: Revolut must keep the order open for at least 10 more minutes.
        val until: ArgumentCaptor<Instant> = ArgumentCaptor.forClass(Instant::class.java)
        verify(orders).findReusable(eq(FLOW_ID), eq("7"), eq(281_160L), eq(7L), capture(until))
        Duration.between(Instant.now(), until.value).toMinutes() shouldBe 9L
    }

    @Test
    fun `reuse - the pay-full path never reuses an order of a pinned phase (payment phase key -1)`() {
        reservation(phase(7, "2677.60", 10))
        acceptCreates()
        service().createCheckout(RESERVATION_ID, false, null, null, "en")
        // prepare + the re-check in the ledger insert
        verify(orders, times(2)).findReusable(eq(FLOW_ID), eq("7"), eq(281_160L), eq(-1L), any())
    }

    @Test
    fun `stale pending - read once, pending then cancelled at Revolut and in the ledger, then a new order`() {
        reservation(phase(7, "2677.60", 10), phase(8, "1000.00", 40))
        val stale = ledgerRow(66, "ord-old", phaseIds = "7,8", amountMinor = 386_160)
        `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(stale), emptyList())
        `when`(orders.lockById(66)).thenReturn(stale)
        `when`(client.getOrderOnce("ord-old")).thenReturn(revolutOrder("ord-old", "pending", 386_160))
        `when`(client.cancelOrder("ord-old")).thenReturn(revolutOrder("ord-old", "cancelled", 386_160))
        acceptCreates()

        service().createCheckout(RESERVATION_ID, null, 7, "key-2", "en").sessionIdOrOrderCode shouldBe "ord-new"

        stale.status shouldBe ReservationCardOrderStatus.CANCELLED
        val calls = inOrder(client)
        calls.verify(client).getOrderOnce("ord-old")
        calls.verify(client).cancelOrder("ord-old")
        calls.verify(client).createOrder(any())
        verify(client, never()).getOrder(ArgumentMatchers.anyString())
    }

    @Test
    fun `stale pending - failed or cancelled at Revolut only closes the ledger row, then a new order`() {
        listOf("failed" to ReservationCardOrderStatus.FAILED, "cancelled" to ReservationCardOrderStatus.CANCELLED).forEach { (state, closed) ->
            clearInvocations(client, orders)
            reservation(phase(7, "2677.60", 10))
            val stale = ledgerRow(66, "ord-old")
            `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(stale), emptyList())
            `when`(orders.lockById(66)).thenReturn(stale)
            `when`(client.getOrderOnce("ord-old")).thenReturn(revolutOrder("ord-old", state))
            acceptCreates()

            service().createCheckout(RESERVATION_ID, null, 7, null, "en").sessionIdOrOrderCode shouldBe "ord-new"

            stale.status shouldBe closed
            verify(client, never()).cancelOrder("ord-old")
        }
    }

    @Test
    fun `stale pending - processing or authorised (or an unknown state) then 409 in progress with its static body, nothing cancelled or created`() {
        listOf("processing", "authorised", "some_new_state").forEach { state ->
            clearInvocations(client, orders)
            reservation(phase(7, "2677.60", 10))
            `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(ledgerRow(66, "ord-busy")))
            `when`(client.getOrderOnce("ord-busy")).thenReturn(revolutOrder("ord-busy", state))

            expectCheckoutError(service(), 409, ApiErrorCodes.CARD_PAYMENT_IN_PROGRESS)

            verify(client, never()).cancelOrder(ArgumentMatchers.anyString())
            verify(client, never()).createOrder(any())
            verify(orders, never()).saveAndFlush(any<ReservationCardOrder>())
        }
    }

    @Test
    fun `stale pending - completed then the completion routine runs outside the create transaction, then 409 already paid with its static body`() {
        reservation(phase(7, "2677.60", 10))
        val completed = revolutOrder("ord-paid", "completed")
        `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(ledgerRow(66, "ord-paid")))
        `when`(client.getOrderOnce("ord-paid")).thenReturn(completed)

        expectCheckoutError(service(), 409, ApiErrorCodes.CARD_ALREADY_PAID)

        verify(completion).applyCompleted(66, completed)
        verify(client, never()).createOrder(any())
        verify(client, never()).cancelOrder(ArgumentMatchers.anyString())

        // A failing completion routine does not turn the answer into a 500: the money is there, the job retries.
        doThrow(IllegalStateException("db down")).`when`(completion).applyCompleted(66, completed)
        shouldThrow<CardAlreadyPaidException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }
    }

    @Test
    fun `stale pending - one completed and one pending order - the pending one is cancelled too, then 409 already paid`() {
        reservation(phase(7, "2677.60", 10), phase(8, "1000.00", 40))
        val pending = ledgerRow(65, "ord-open", phaseIds = "7,8", amountMinor = 386_160)
        `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(pending, ledgerRow(66, "ord-paid")))
        `when`(orders.lockById(65)).thenReturn(pending)
        `when`(client.getOrderOnce("ord-open")).thenReturn(revolutOrder("ord-open", "pending", 386_160))
        `when`(client.cancelOrder("ord-open")).thenReturn(revolutOrder("ord-open", "cancelled", 386_160))
        `when`(client.getOrderOnce("ord-paid")).thenReturn(revolutOrder("ord-paid", "completed"))

        shouldThrow<CardAlreadyPaidException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }

        pending.status shouldBe ReservationCardOrderStatus.CANCELLED
        verify(completion).applyCompleted(eq(66L), any())
        verify(client, never()).createOrder(any())
    }

    @Test
    fun `stale pending - Revolut refuses the cancel (payer is paying) then 409, the ledger row stays PENDING`() {
        reservation(phase(7, "2677.60", 10))
        val stale = ledgerRow(66, "ord-old")
        `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(stale))
        `when`(client.getOrderOnce("ord-old")).thenReturn(revolutOrder("ord-old", "pending"))
        `when`(client.cancelOrder("ord-old")).thenThrow(
            HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "", HttpHeaders(), ByteArray(0), null),
        )

        shouldThrow<CardPaymentInProgressException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }

        stale.status shouldBe ReservationCardOrderStatus.PENDING
        verify(client, never()).createOrder(any())
    }

    @Test
    fun `stale pending - Revolut unreachable or not knowing the order then 502, nothing changes`() {
        reservation(phase(7, "2677.60", 10))
        val stale = ledgerRow(66, "ord-old")
        `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(stale))
        `when`(client.getOrderOnce("ord-old")).thenThrow(ResourceAccessException("timeout"))
        expectCheckoutError(service(), 502, ApiErrorCodes.CARD_PAYMENT_PROVIDER_ERROR)

        doReturn(null).`when`(client).getOrderOnce("ord-old")
        shouldThrow<CardPaymentProviderException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }

        stale.status shouldBe ReservationCardOrderStatus.PENDING
        verify(client, never()).cancelOrder(ArgumentMatchers.anyString())
        verify(client, never()).createOrder(any())
    }

    @Test
    fun `stale pending - another pending order right after the cleanup (second tab) then 409, no loop`() {
        reservation(phase(7, "2677.60", 10))
        val first = ledgerRow(66, "ord-old")
        `when`(orders.findPendingByReservationFlow(FLOW_ID)).thenReturn(listOf(first), listOf(ledgerRow(67, "ord-tab2")))
        `when`(orders.lockById(66)).thenReturn(first)
        `when`(client.getOrderOnce("ord-old")).thenReturn(revolutOrder("ord-old", "pending"))
        `when`(client.cancelOrder("ord-old")).thenReturn(revolutOrder("ord-old", "cancelled"))

        shouldThrow<CardPaymentInProgressException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }

        verify(client, never()).getOrderOnce("ord-tab2")
        verify(client, never()).createOrder(any())
    }

    @Test
    fun `Revolut create error then 502 with its static body and no ledger row`() {
        reservation(phase(7, "2677.60", 10))
        listOf(
            HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", HttpHeaders(), ByteArray(0), null),
            HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "", HttpHeaders(), ByteArray(0), null),
            ResourceAccessException("read timeout"),
        ).forEach { failure ->
            doThrow(failure).`when`(client).createOrder(any())
            expectCheckoutError(service(), 502, ApiErrorCodes.CARD_PAYMENT_PROVIDER_ERROR)
        }
        verify(orders, never()).saveAndFlush(any<ReservationCardOrder>())
        verify(client, never()).cancelOrder(ArgumentMatchers.anyString())
    }

    @Test
    fun `checkout URL missing or on a foreign origin then the order is cancelled, 502, no ledger row`() {
        reservation(phase(7, "2677.60", 10))
        listOf(null, "https://evil.example/pay", "http://checkout.revolut.com/payment-link/x").forEach { url ->
            clearInvocations(client)
            `when`(client.createOrder(any())).thenReturn(revolutOrder("ord-new", "pending", checkout = url))
            shouldThrow<CardPaymentProviderException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }
            verify(client).cancelOrder("ord-new")
        }
        verify(orders, never()).saveAndFlush(any<ReservationCardOrder>())
    }

    @Test
    fun `a concurrent identical checkout inserted its row first then its checkout is returned and our new order cancelled`() {
        reservation(phase(7, "2677.60", 10))
        acceptCreates()
        `when`(orders.findReusable(eq(FLOW_ID), eq("7"), eq(281_160L), eq(7L), any())).thenReturn(null, ledgerRow(78, "ord-first"))

        val dto = service().createCheckout(RESERVATION_ID, null, 7, null, "en")

        dto shouldBe CheckoutSessionDto("ord-first", CheckoutSessionStatusEnum.PAYMENT_PENDING, "https://checkout.revolut.com/payment-link/ord-first")
        verify(client).cancelOrder("ord-new")
        verify(orders, never()).saveAndFlush(any<ReservationCardOrder>())
    }

    @Test
    fun `M1 - an open Stripe session is expired only on target phases that carry one, and stripeSessionId is kept`() {
        val withSession = phase(7, "2677.60", 10, stripeSession = "cs_live_target")
        val other = phase(8, "1000.00", 40, stripeSession = "cs_live_other")
        reservation(withSession, phase(9, "300.00", 20), other)
        acceptCreates()

        service().createCheckout(RESERVATION_ID, null, 7, null, "en")
        verify(stripeSessions).expireStripeSessionSafely("cs_live_target")
        verify(stripeSessions, never()).expireStripeSessionSafely("cs_live_other")
        withSession.stripeSessionId shouldBe "cs_live_target"

        clearInvocations(stripeSessions)
        service().createCheckout(RESERVATION_ID, null, 9, null, "en")
        verifyNoInteractions(stripeSessions)

        // Pay full: every unpaid phase is a target — each session once, the phase without one is skipped.
        service().createCheckout(RESERVATION_ID, true, null, null, "en")
        verify(stripeSessions).expireStripeSessionSafely("cs_live_target")
        verify(stripeSessions).expireStripeSessionSafely("cs_live_other")
    }

    @Test
    fun `M3 - expire_pending_after and expires_at per reservation state`() {
        fun lifetime(
            sysStatus: ReservationStatus,
            optionExpiresAt: LocalDateTime?,
        ): Pair<String, Long> {
            clearInvocations(client, orders)
            reservation(phase(7, "2677.60", 10), sysStatus = sysStatus, optionExpiresAt = optionExpiresAt)
            val (requestCaptor, rowCaptor) = acceptCreates()
            service().createCheckout(RESERVATION_ID, null, 7, null, "en")
            val row = capturedRow(rowCaptor)
            return capturedRequest(requestCaptor).expirePendingAfter!! to Duration.between(Instant.now(), row.expiresAt).toMinutes()
        }

        val now = LocalDateTime.now()
        lifetime(ReservationStatus.OPTION, now.plusHours(3)) shouldBe ("PT1H" to 59L)
        lifetime(ReservationStatus.OPTION, now.plusMinutes(40)).let { (iso, minutes) ->
            iso shouldStartWith "PT39M"
            minutes shouldBe 39L
        }
        lifetime(ReservationStatus.OPTION, now.plusMinutes(5)) shouldBe ("PT15M" to 14L)
        lifetime(ReservationStatus.OPTION, now.minusHours(2)) shouldBe ("PT1H" to 59L) // grace window: Stripe parity
        lifetime(ReservationStatus.OPTION_WAITING, now.plusMinutes(5)) shouldBe ("PT1H" to 59L)
        lifetime(ReservationStatus.RESERVATION, now.plusMinutes(5)) shouldBe ("PT1H" to 59L)
        lifetime(ReservationStatus.OPTION, null) shouldBe ("PT1H" to 59L)
    }

    @Test
    fun `a target phase paid meanwhile (fresh check under the lock) then 409 already paid, no Stripe or Revolut call`() {
        reservation(phase(7, "2677.60", 10, stripeSession = "cs_live_target"))
        `when`(phases.countByIdInAndPaidOnIsNotNull(listOf(7L))).thenReturn(1L)

        expectCheckoutError(service(), 409, ApiErrorCodes.CARD_ALREADY_PAID)

        verifyNoInteractions(client, stripeSessions)
    }

    @Test
    fun `a lock wait running into lock_timeout then 409 in progress`() {
        reservation(phase(7, "2677.60", 10))
        `when`(orders.lockKey("reservation-card-order|$FLOW_ID")).thenThrow(CannotAcquireLockException("lock_timeout"))

        expectCheckoutError(service(), 409, ApiErrorCodes.CARD_PAYMENT_IN_PROGRESS)
        verifyNoInteractions(client)
    }

    @Test
    fun `selection 400s, unknown reservation 404 and disabled Revolut 503 happen before any Revolut call`() {
        reservation(phase(7, "2677.60", 10, paid = true), phase(8, "1000.00", 40))
        shouldThrow<ParameterValidationException> { service().createCheckout(RESERVATION_ID, null, 7, null, "en") }
        shouldThrow<ParameterValidationException> { service().createCheckout(RESERVATION_ID, null, 99, null, "en") }
        shouldThrow<ParameterValidationException> { service().createCheckout(RESERVATION_ID, null, null, null, "en") }
        `when`(reservations.findById(43L)).thenReturn(Optional.empty())
        shouldThrow<ReservationNotExistException> { service().createCheckout(43, null, 7, null, "en") }
        verifyNoInteractions(client, stripeSessions)

        clearInvocations(reservations)
        shouldThrow<CardPaymentsUnavailableException> { service(revolutProperties(secretKey = "")).createCheckout(RESERVATION_ID, null, 8, null, "en") }
        verifyNoInteractions(reservations, client)
    }

    @Test
    fun `status - unknown or malformed reference is null (router - Stripe, then 404) without a Revolut call`() {
        service().checkStatus("ord-unknown").shouldBeNull()
        service().checkStatus("cs_test_a1B2c3").shouldBeNull()
        service().checkStatus("../../api/orders").shouldBeNull()
        verify(orders, never()).findByProviderOrderId("../../api/orders")
        verifyNoInteractions(client)
    }

    @Test
    fun `status - PENDING asks Revolut once (claimCheck), completed goes through the completion routine, COMPLETED is PAYMENT_SUCCESS`() {
        val row = ledgerRow(66, "ord-1")
        `when`(orders.findByProviderOrderId("ord-1")).thenReturn(row)
        `when`(orders.findById(66)).thenReturn(Optional.of(row))
        `when`(orders.claimCheck(66)).thenReturn(1)
        val completed = revolutOrder("ord-1", "completed")
        `when`(client.getOrderOnce("ord-1")).thenReturn(completed)
        // Stand-in for stage C: the routine records the completion.
        doAnswer { row.status = ReservationCardOrderStatus.COMPLETED }.`when`(completion).applyCompleted(66, completed)

        service().checkStatus("ord-1") shouldBe CheckoutSessionDto("ord-1", CheckoutSessionStatusEnum.PAYMENT_SUCCESS)
        verify(completion).applyCompleted(66, completed)
        verify(client, never()).getOrder(ArgumentMatchers.anyString())

        // COMPLETED: no claim, no Revolut call any more.
        clearInvocations(client, orders)
        service().checkStatus("ord-1") shouldBe CheckoutSessionDto("ord-1", CheckoutSessionStatusEnum.PAYMENT_SUCCESS)
        verify(orders, never()).claimCheck(anyLong())
        verifyNoInteractions(client)
    }

    @Test
    fun `status - claimed within 10 s, Revolut errors and disabled Revolut keep the stored status, failed closes the row`() {
        val row = ledgerRow(66, "ord-1")
        `when`(orders.findByProviderOrderId("ord-1")).thenReturn(row)
        `when`(orders.findById(66)).thenReturn(Optional.of(row))
        `when`(orders.lockById(66)).thenReturn(row)

        `when`(orders.claimCheck(66)).thenReturn(0)
        service().checkStatus("ord-1")!!.status shouldBe CheckoutSessionStatusEnum.PAYMENT_PENDING
        verifyNoInteractions(client)

        `when`(orders.claimCheck(66)).thenReturn(1)
        `when`(client.getOrderOnce("ord-1")).thenThrow(ResourceAccessException("timeout"))
        service().checkStatus("ord-1")!!.status shouldBe CheckoutSessionStatusEnum.PAYMENT_PENDING

        clearInvocations(client, orders)
        service(revolutProperties(secretKey = "")).checkStatus("ord-1")!!.status shouldBe CheckoutSessionStatusEnum.PAYMENT_PENDING
        verify(orders, never()).claimCheck(anyLong())
        verifyNoInteractions(client)

        doReturn(revolutOrder("ord-1", "failed")).`when`(client).getOrderOnce("ord-1")
        service().checkStatus("ord-1")!!.status shouldBe CheckoutSessionStatusEnum.PAYMENT_PENDING
        row.status shouldBe ReservationCardOrderStatus.FAILED
        verifyNoInteractions(completion)
    }

    private companion object {
        const val RESERVATION_ID = 42L
        const val FLOW_ID = 900L
    }
}
