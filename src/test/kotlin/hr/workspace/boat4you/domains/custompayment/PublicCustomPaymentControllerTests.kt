package hr.workspace.boat4you.domains.custompayment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.custompayment.controllers.PublicCustomPaymentController
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentDraft
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentService
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentTx
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentVerdict
import hr.workspace.boat4you.domains.custompayment.service.OrderSnapshot
import hr.workspace.boat4you.domains.custompayment.service.PendingInsert
import hr.workspace.boat4you.domains.payment.revolut.RevolutCreateOrderRequest
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantClient
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantOrderData
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutPayment
import hr.workspace.boat4you.domains.payment.revolut.RevolutProperties
import hr.workspace.boat4you.domains.payment.revolut.revolutProperties
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** HTTP contract of `/public/payments/custom`: the real service with Revolut and the database mocked. */
class PublicCustomPaymentControllerTests {
    private val linkKey = "Kx7_".repeat(10)
    private val token = "0b6f7c1e-3c55-4d1e-9a7e-1f2d3c4b5a69"
    private val checkoutUrl = "https://checkout.revolut.com/payment-link/abc123"
    private val tx: CustomPaymentTx = mock(CustomPaymentTx::class.java)
    private val repository: CustomPaymentRepository = mock(CustomPaymentRepository::class.java)
    private val reservations: ReservationRepository = mock(ReservationRepository::class.java)
    private val client: RevolutMerchantClient = mock(RevolutMerchantClient::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> capture(captor: ArgumentCaptor<T>): T = captor.capture() ?: null as T

    private fun mvc(properties: RevolutProperties = revolutProperties(linkKeys = "boat4you=$linkKey;europe-yachts=${"e".repeat(32)}")) =
        MockMvcBuilders
            .standaloneSetup(PublicCustomPaymentController(CustomPaymentService(properties, tx, repository, reservations, client)))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    private fun body(
        reference: String = "100116/2027",
        amountMinor: String = "110900",
        email: String = "payer@example.com",
        acceptTerms: String = "true",
        locale: String = "de",
        site: String = "boat4you",
        key: String = linkKey,
    ) = """{"reference":"$reference","amountMinor":$amountMinor,"email":"$email","acceptTerms":$acceptTerms,""" +
        """"locale":"$locale","site":"$site","linkKey":"$key"}"""

    private fun row(
        status: CustomPaymentStatus = CustomPaymentStatus.PENDING,
        createdAt: Instant = Instant.now(),
        orderId: String? = "ord-1",
    ) = CustomPayment().apply {
        id = 7
        publicToken = UUID.fromString(token)
        site = "boat4you"
        reference = "100116/2027"
        amountMinor = 110_900
        payerEmail = "payer@example.com"
        providerOrderId = orderId
        checkoutUrl = this@PublicCustomPaymentControllerTests.checkoutUrl
        this.status = status
        this.createdAt = createdAt
    }

    private fun order(
        state: String = "pending",
        url: String? = checkoutUrl,
    ) = RevolutOrder(
        id = "ord-1",
        type = "payment",
        state = state,
        amount = 110_900,
        currency = "EUR",
        outstandingAmount = 0,
        checkoutUrl = url,
        merchantOrderData = RevolutMerchantOrderData(token),
        payments = listOf(RevolutPayment("pay-1", state)),
        createdAt = Instant.parse("2026-10-13T10:00:00Z"),
        updatedAt = Instant.parse("2026-10-13T10:00:00Z"),
    )

    private fun postOrder(
        content: String,
        properties: RevolutProperties? = null,
    ) = (if (properties == null) mvc() else mvc(properties)).post("/public/payments/custom/orders") {
        contentType = MediaType.APPLICATION_JSON
        this.content = content
        header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
    }

    @Test
    fun `config - right pair 200 never cached, wrong key or site 404, disabled reports enabled=false`() {
        mvc().get("/public/payments/custom/config?site=boat4you&linkKey=$linkKey").andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.enabled") { value(true) }
            jsonPath("$.minAmountMinor") { value(5000) }
            jsonPath("$.maxAmountMinor") { value(10_000_000) }
            jsonPath("$.currency") { value("EUR") }
            jsonPath("$.termsVersion") { value("2026-10-08") }
        }
        listOf(
            "site=boat4you&linkKey=${linkKey.dropLast(1)}x",
            "site=europe-yachts&linkKey=$linkKey",
            "site=nope&linkKey=$linkKey",
            "site=boat4you&linkKey=",
        ).forEach { query ->
            mvc().get("/public/payments/custom/config?$query").andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(8002) }
            }
        }
        mvc(revolutProperties(secretKey = "", linkKeys = "boat4you=$linkKey"))
            .get("/public/payments/custom/config?site=boat4you&linkKey=$linkKey")
            .andExpect {
                status { isOk() }
                jsonPath("$.enabled") { value(false) }
            }
    }

    @Test
    fun `create - invalid fields 400 and Revolut is never called`() {
        val invalid =
            listOf(
                body(amountMinor = "\"abc\""),
                body(amountMinor = "4999"),
                body(amountMinor = "5000.5"),
                body(amountMinor = "10000001"),
                body(amountMinor = "null"),
                body(reference = "ab"),
                body(reference = "100116<script>"),
                body(reference = "   "),
                body(email = "not-an-email"),
                body(email = "a@b"),
                body(acceptTerms = "false"),
                body(acceptTerms = "null"),
                body(locale = "xx"),
                """{"reference":"100116/2027"}""",
                "not json",
            )
        invalid.forEach { content ->
            postOrder(content).andExpect { status { isBadRequest() } }
        }
        postOrder(body(amountMinor = "\"abc\"")).andExpect { jsonPath("$.code") { value(1101) } }
        postOrder(body(amountMinor = "10000001")).andExpect { jsonPath("$.code") { value(1102) } }
        verifyNoInteractions(client, tx)
    }

    @Test
    fun `create - wrong link key 404, Revolut not configured 503 8001, too many per e-mail 429 8003`() {
        postOrder(body(key = "w".repeat(40))).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(8002) }
        }
        postOrder(body(site = "europe-yachts")).andExpect { status { isNotFound() } }

        postOrder(body(), revolutProperties(secretKey = "", linkKeys = "boat4you=$linkKey")).andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value(8001) }
        }

        `when`(repository.countByEmailSince(ArgumentMatchers.anyString(), any())).thenReturn(5L)
        postOrder(body()).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.code") { value(8003) }
        }
        verifyNoInteractions(client, tx)
    }

    @Test
    fun `create - happy path builds the Revolut order exactly and returns the hosted checkout`() {
        `when`(tx.insertPending(any(), any())).thenReturn(PendingInsert(row(orderId = null), existing = false))
        `when`(client.createOrder(any())).thenReturn(order())

        postOrder(body()).andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.redirectUrl") { value(checkoutUrl) }
            jsonPath("$.ref") { value(token) }
        }

        val draft: ArgumentCaptor<CustomPaymentDraft> = ArgumentCaptor.forClass(CustomPaymentDraft::class.java)
        val since: ArgumentCaptor<Instant> = ArgumentCaptor.forClass(Instant::class.java)
        verify(tx).insertPending(capture(draft), capture(since))
        draft.value.site shouldBe "boat4you"
        draft.value.reference shouldBe "100116/2027"
        draft.value.amountMinor shouldBe 110_900L
        draft.value.payerEmail shouldBe "payer@example.com"
        draft.value.locale shouldBe "de"
        draft.value.clientIp shouldBe "203.0.113.9"
        draft.value.termsVersion shouldBe "2026-10-08"
        draft.value.reservationId shouldBe null
        Duration.between(since.value, Instant.now()).toMinutes() shouldBe 50L

        val request: ArgumentCaptor<RevolutCreateOrderRequest> = ArgumentCaptor.forClass(RevolutCreateOrderRequest::class.java)
        verify(client).createOrder(capture(request))
        with(request.value) {
            amount shouldBe 110_900L
            currency shouldBe "EUR"
            captureMode shouldBe "automatic"
            description shouldBe "Boat4You payment — 100116/2027"
            redirectUrl shouldBe "https://www.boat4you.com/de/pay/$linkKey/success?ref=$token"
            merchantOrderData?.reference shouldBe token
            metadata shouldBe mapOf("customPaymentId" to "7")
            expirePendingAfter shouldBe "PT1H"
            enforceChallenge shouldBe "forced"
        }
        val json = jacksonObjectMapper().writeValueAsString(request.value)
        json shouldContain "\"capture_mode\":\"automatic\""
        json shouldContain "\"merchant_order_data\":{\"reference\":\"$token\"}"
        json shouldNotContain "customer"
        json shouldNotContain "payer@example.com"
        verify(tx).attachOrder(7, "ord-1", checkoutUrl)
    }

    @Test
    fun `create - english has no locale prefix, a sister site uses its own domain and brand`() {
        `when`(tx.insertPending(any(), any())).thenReturn(PendingInsert(row(orderId = null).apply { site = "europe-yachts" }, existing = false))
        `when`(client.createOrder(any())).thenReturn(order())
        postOrder(body(site = "europe-yachts", key = "e".repeat(32), locale = "en")).andExpect { status { isOk() } }
        val request: ArgumentCaptor<RevolutCreateOrderRequest> = ArgumentCaptor.forClass(RevolutCreateOrderRequest::class.java)
        verify(client).createOrder(capture(request))
        request.value.redirectUrl shouldBe "https://www.europe-yachts.com/pay/${"e".repeat(32)}/success?ref=$token"
        request.value.description shouldBe "Europe Yachts Charter payment — 100116/2027"
    }

    @Test
    fun `create - same attempt again returns the existing checkout without a new Revolut order`() {
        `when`(tx.insertPending(any(), any())).thenReturn(PendingInsert(row(), existing = true))
        postOrder(body()).andExpect {
            status { isOk() }
            jsonPath("$.redirectUrl") { value(checkoutUrl) }
            jsonPath("$.ref") { value(token) }
        }
        verifyNoInteractions(client)
    }

    @Test
    fun `create - Revolut error or a checkout on a foreign origin - 502 8004 and the attempt is closed`() {
        `when`(tx.insertPending(any(), any())).thenReturn(PendingInsert(row(orderId = null), existing = false))
        `when`(client.createOrder(any())).thenThrow(ResourceAccessException("timeout"))
        postOrder(body()).andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value(8004) }
        }
        verify(tx).markCreateFailed(7, null)

        doThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", HttpHeaders(), ByteArray(0), null)).`when`(client).createOrder(any())
        postOrder(body()).andExpect { status { isBadGateway() } }

        doReturn(order(url = "https://evil.example/checkout")).`when`(client).createOrder(any())
        postOrder(body()).andExpect { status { isBadGateway() } }
        doReturn(order(url = "http://checkout.revolut.com/x")).`when`(client).createOrder(any())
        postOrder(body()).andExpect { status { isBadGateway() } }
        verify(tx, times(2)).markCreateFailed(7, "ord-1")
        verify(tx, never()).attachOrder(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString())
    }

    @Test
    fun `status - unknown, malformed or older than 30 days 404, otherwise only the five public fields`() {
        mvc().get("/public/payments/custom/orders/not-a-uuid").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(8002) }
        }
        mvc().get("/public/payments/custom/orders/${UUID.randomUUID()}").andExpect { status { isNotFound() } }

        `when`(repository.findByPublicToken(UUID.fromString(token))).thenReturn(row(createdAt = Instant.now().minus(Duration.ofDays(31))))
        mvc().get("/public/payments/custom/orders/$token").andExpect { status { isNotFound() } }

        // Checked less than 10 s ago (claimCheck = 0): the stored status, Revolut not asked.
        `when`(repository.findByPublicToken(UUID.fromString(token))).thenReturn(row())
        mvc().get("/public/payments/custom/orders/$token").andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.amountMinor") { value(110_900) }
            jsonPath("$.currency") { value("EUR") }
            jsonPath("$.reference") { value("100116/2027") }
            jsonPath("$.site") { value("boat4you") }
            jsonPath("$.payerEmail") { doesNotExist() }
            jsonPath("$.id") { doesNotExist() }
        }
        verifyNoInteractions(client)
    }

    @Test
    fun `status - a claimed check reads the order and applies the verdict, a Revolut hiccup is swallowed`() {
        `when`(repository.findByPublicToken(UUID.fromString(token))).thenReturn(row())
        `when`(repository.claimCheck(7)).thenReturn(1)
        val completed = order(state = "completed")
        `when`(client.getOrder("ord-1")).thenReturn(completed)
        `when`(repository.findById(7)).thenReturn(Optional.of(row(status = CustomPaymentStatus.COMPLETED)))

        mvc().get("/public/payments/custom/orders/$token").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
        }
        verify(tx).applyVerdict(7, CustomPaymentVerdict.Complete("pay-1"), OrderSnapshot.of(completed).toJson(), null)

        doThrow(ResourceAccessException("timeout")).`when`(client).getOrder("ord-1")
        mvc().get("/public/payments/custom/orders/$token").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PENDING") }
        }
    }
}
