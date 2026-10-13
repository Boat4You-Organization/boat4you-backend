package hr.workspace.boat4you.domains.payment.revolut

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.retry.annotation.EnableRetry
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.function.Supplier

/** HTTP contract of the Merchant API client against a mocked Revolut (retry proxy from a real @EnableRetry context). */
class RevolutMerchantClientTests {
    private val properties = revolutProperties()
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val context =
        AnnotationConfigApplicationContext().apply {
            register(RetryConfig::class.java)
            registerBean(
                RevolutMerchantClient::class.java,
                Supplier { RevolutMerchantClient(RevolutMerchantClient.configure(builder, properties), properties) },
            )
            refresh()
        }
    private val client = context.getBean(RevolutMerchantClient::class.java)

    private val orderId = "6516e61c-d279-a454-a837-bc52ce55ed49"
    private val ordersUrl = "$TEST_BASE_URL/api/orders"

    @Configuration
    @EnableRetry
    class RetryConfig

    @AfterEach
    fun close() {
        context.close()
    }

    private fun orderJson(
        state: String = "pending",
        extra: String = "",
    ) = """
        {
          "id": "$orderId",
          "token": "tok_should_be_ignored",
          "type": "payment",
          "state": "$state",
          "amount": 110900,
          "currency": "EUR",
          "outstanding_amount": 110900,
          "checkout_url": "https://checkout.revolut.com/payment-link/abc",
          "merchant_order_data": { "reference": "0b6f0c1e-1d7a-4c34-9d5e-3f3f2f1b9a10", "url": "https://x" },
          "customer": { "id": "cus_1", "email": "payer@example.com" },
          "payments": [
            { "id": "pay_1", "state": "declined", "created_at": "2026-10-13T10:00:01.123456Z",
              "payment_method": { "type": "card", "card_brand": "visa", "card_last_four": "1234" } }
          ],
          "brand_new_field": { "nested": true },
          "created_at": "2026-10-13T10:00:00.000000Z",
          "updated_at": "2026-10-13T10:05:00Z"$extra
        }
        """.trimIndent()

    private val createRequest =
        RevolutCreateOrderRequest(
            amount = 110900,
            currency = "EUR",
            description = "Boat4You payment — 100116/2027",
            redirectUrl = "https://www.boat4you.com/de/pay/key/success?ref=0b6f0c1e",
            merchantOrderData = RevolutMerchantOrderData(reference = "0b6f0c1e-1d7a-4c34-9d5e-3f3f2f1b9a10"),
            metadata = mapOf("customPaymentId" to "42"),
            expirePendingAfter = "PT1H",
            enforceChallenge = "forced",
        )

    @Test
    fun `create order - bearer and version headers, snake_case body without a customer field`() {
        server
            .expect(ExpectedCount.once(), requestTo(ordersUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer $TEST_SECRET_KEY"))
            .andExpect(header("Revolut-Api-Version", "2026-08-17"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.amount").value(110900))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.capture_mode").value("automatic"))
            .andExpect(jsonPath("$.redirect_url").value("https://www.boat4you.com/de/pay/key/success?ref=0b6f0c1e"))
            .andExpect(jsonPath("$.merchant_order_data.reference").value("0b6f0c1e-1d7a-4c34-9d5e-3f3f2f1b9a10"))
            .andExpect(jsonPath("$.metadata.customPaymentId").value("42"))
            .andExpect(jsonPath("$.expire_pending_after").value("PT1H"))
            .andExpect(jsonPath("$.enforce_challenge").value("forced"))
            .andExpect(jsonPath("$.customer").doesNotExist())
            .andExpect(jsonPath("$.captureMode").doesNotExist())
            .andRespond(withSuccess(orderJson(), MediaType.APPLICATION_JSON))

        val order = client.createOrder(createRequest)

        order.id shouldBe orderId
        order.checkoutUrl shouldBe "https://checkout.revolut.com/payment-link/abc"
        server.verify()
    }

    @Test
    fun `null optional request fields are not sent`() {
        server
            .expect(requestTo(ordersUrl))
            .andExpect(jsonPath("$.description").doesNotExist())
            .andExpect(jsonPath("$.metadata").doesNotExist())
            .andExpect(jsonPath("$.redirect_url").doesNotExist())
            .andRespond(withSuccess(orderJson(), MediaType.APPLICATION_JSON))

        client.createOrder(RevolutCreateOrderRequest(amount = 5000, currency = "EUR"))
        server.verify()
    }

    @Test
    fun `unknown response fields are ignored and known ones are mapped`() {
        server
            .expect(requestTo("$ordersUrl/$orderId"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer $TEST_SECRET_KEY"))
            .andExpect(header("Revolut-Api-Version", "2026-08-17"))
            .andRespond(
                withSuccess(orderJson(state = "completed", extra = """, "related_order_id": null, "refunded_amount": 0"""), MediaType.APPLICATION_JSON),
            )

        val order = client.getOrder(orderId)!!

        order.type shouldBe "payment"
        order.state shouldBe "completed"
        order.amount shouldBe 110900
        order.currency shouldBe "EUR"
        order.outstandingAmount shouldBe 110900
        order.refundedAmount shouldBe 0
        order.relatedOrderId shouldBe null
        order.merchantOrderData?.reference shouldBe "0b6f0c1e-1d7a-4c34-9d5e-3f3f2f1b9a10"
        order.payments shouldHaveSize 1
        order.payments[0].id shouldBe "pay_1"
        order.payments[0].state shouldBe "declined"
        order.payments[0].createdAt shouldBe Instant.parse("2026-10-13T10:00:01.123456Z")
        order.createdAt shouldBe Instant.parse("2026-10-13T10:00:00Z")
        order.updatedAt shouldBe Instant.parse("2026-10-13T10:05:00Z")
        server.verify()
    }

    @Test
    fun `missing or null payments become an empty list`() {
        val noPayments =
            """{"id":"$orderId","type":"payment","state":"pending","amount":5000,"currency":"EUR",""" +
                """"payments":null,"created_at":"2026-10-13T10:00:00Z","updated_at":"2026-10-13T10:00:00Z"}"""
        server.expect(requestTo("$ordersUrl/$orderId")).andRespond(withSuccess(noPayments, MediaType.APPLICATION_JSON))

        client.getOrder(orderId)!!.payments.shouldBeEmpty()
        server.verify()
    }

    @Test
    fun `create order is never retried on a 5xx`() {
        server
            .expect(ExpectedCount.once(), requestTo(ordersUrl))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())

        shouldThrow<HttpServerErrorException> { client.createOrder(createRequest) }
        server.verify()
    }

    @Test
    fun `get order is retried on a 5xx and then succeeds`() {
        server.expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId")).andRespond(withServerError())
        server
            .expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId"))
            .andRespond(withSuccess(orderJson(state = "completed"), MediaType.APPLICATION_JSON))

        client.getOrder(orderId)!!.state shouldBe "completed"
        server.verify()
    }

    @Test
    fun `get order gives up after 3 attempts, a 4xx is not retried`() {
        server.expect(ExpectedCount.times(3), requestTo("$ordersUrl/$orderId")).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        shouldThrow<HttpServerErrorException> { client.getOrder(orderId) }
        server.verify()

        server.reset()
        server.expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        shouldThrow<HttpClientErrorException.Unauthorized> { client.getOrder(orderId) }
        server.verify()
    }

    @Test
    fun `get order once - one attempt only (a customer is waiting), same answer otherwise`() {
        server.expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId")).andRespond(withServerError())
        shouldThrow<HttpServerErrorException> { client.getOrderOnce(orderId) }
        server.verify()

        server.reset()
        server
            .expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer $TEST_SECRET_KEY"))
            .andRespond(withSuccess(orderJson(state = "completed"), MediaType.APPLICATION_JSON))
        client.getOrderOnce(orderId)!!.state shouldBe "completed"
        server.verify()

        server.reset()
        server.expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId")).andRespond(withResourceNotFound())
        client.getOrderOnce(orderId) shouldBe null
        server.verify()
    }

    @Test
    fun `get order - 404 is null`() {
        server
            .expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId"))
            .andRespond(withResourceNotFound().body("""{"code":"not_found","message":"Order not found"}""").contentType(MediaType.APPLICATION_JSON))

        client.getOrder(orderId) shouldBe null
        server.verify()
    }

    @Test
    fun `cancel order - POST to the cancel path, not retried`() {
        server
            .expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer $TEST_SECRET_KEY"))
            .andExpect(header("Revolut-Api-Version", "2026-08-17"))
            .andRespond(withSuccess(orderJson(state = "cancelled"), MediaType.APPLICATION_JSON))

        client.cancelOrder(orderId)!!.state shouldBe "cancelled"
        server.verify()

        server.reset()
        server.expect(ExpectedCount.once(), requestTo("$ordersUrl/$orderId/cancel")).andRespond(withServerError())
        shouldThrow<HttpServerErrorException> { client.cancelOrder(orderId) }
        server.verify()
    }

    @Test
    fun `errors are logged as status plus Revolut code only, and the exception carries no body`() {
        val logger = LoggerFactory.getLogger(RevolutMerchantClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            server
                .expect(requestTo(ordersUrl))
                .andRespond(
                    withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"code":"invalid_request\n","message":"payer@example.com is not allowed","timestamp":1}"""),
                )

            val error = shouldThrow<HttpClientErrorException> { client.createOrder(createRequest) }

            error.statusCode.value() shouldBe 422
            error.responseBodyAsString shouldBe ""
            error.message!! shouldNotContain "payer@example.com"
            val lines = appender.list.map { it.formattedMessage }
            lines shouldHaveSize 1
            lines[0] shouldContain "422"
            lines[0] shouldContain "code=invalid_request"
            lines[0] shouldNotContain "payer@example.com"
            lines[0] shouldNotContain TEST_SECRET_KEY
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `disabled configuration or a malformed id never reaches Revolut`() {
        val disabled = revolutProperties(secretKey = "")
        val offline = RevolutMerchantClient(RevolutMerchantClient.configure(RestClient.builder(), disabled), disabled)
        shouldThrow<IllegalStateException> { offline.createOrder(createRequest) }
        shouldThrow<IllegalStateException> { offline.getOrder(orderId) }
        shouldThrow<IllegalStateException> { offline.getOrderOnce(orderId) }
        shouldThrow<IllegalStateException> { offline.cancelOrder(orderId) }

        shouldThrow<IllegalArgumentException> { client.getOrder("../refunds") }
        shouldThrow<IllegalArgumentException> { client.getOrderOnce("../refunds") }
        shouldThrow<IllegalArgumentException> { client.cancelOrder("") }
        server.verify()
    }
}
