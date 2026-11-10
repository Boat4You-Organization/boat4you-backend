package hr.workspace.boat4you.domains.reservation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.stripe.model.checkout.Session
import hr.workspace.boat4you.common.errorhandling.ApiErrorCodes
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.payment.mollie.MollieClient
import hr.workspace.boat4you.domains.payment.mollie.MollieProperties
import hr.workspace.boat4you.domains.payment.mollie.TEST_API_KEY
import hr.workspace.boat4you.domains.payment.mollie.booking.BookingCardAlertMailer
import hr.workspace.boat4you.domains.payment.mollie.booking.BookingCardCompletionFollowUp
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingCreateTx
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingPaymentService
import hr.workspace.boat4you.domains.payment.mollie.booking.MollieBookingProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.ReservationCardOrderTx
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.mollieProperties
import hr.workspace.boat4you.domains.reservation.controllers.CardPaymentController
import hr.workspace.boat4you.domains.reservation.controllers.PublicCardPaymentController
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.service.StripePaymentService
import hr.workspace.boat4you.domains.reservation.service.StripeSessionExpirer
import hr.workspace.boat4you.domains.reservation.service.payment.CardAlreadyPaidException
import hr.workspace.boat4you.domains.reservation.service.payment.CardAmountAboveLimitException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentInProgressException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProvider
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentRouter
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentsUnavailableException
import hr.workspace.boat4you.domains.reservation.service.payment.CardProvider
import hr.workspace.boat4you.domains.reservation.service.payment.StripeCardPaymentProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration

/**
 * HTTP contract of the provider-neutral `/secured|public/payments/card` endpoints: provider routing by
 * `application.payment.card-provider`, the Stripe mapping (= the old Stripe controllers), 404 / 409 / 502 / 503
 * bodies through ApiErrorHandler. Stripe and the database are mocked; Mollie is a fake provider.
 */
class CardPaymentControllerTests {
    private val stripeService: StripePaymentService = mock(StripePaymentService::class.java)
    private val phases: ReservationPaymentPhaseRepository = mock(ReservationPaymentPhaseRepository::class.java)
    private val mollie = FakeMollieProvider()
    private val stripeSession =
        Session().apply {
            id = "cs_test_a1B2c3"
            url = "https://checkout.stripe.com/c/pay/cs_test_a1B2c3"
        }

    private class FakeMollieProvider : CardPaymentProvider {
        override val provider = CardProvider.MOLLIE
        var failWith: RuntimeException? = null
        val calls = mutableListOf<List<Any?>>()
        val klarnaSeen = mutableListOf<KlarnaBillingRequest?>()
        val known = mutableMapOf<String, CheckoutSessionDto>()

        override fun createCheckout(
            reservationId: Long,
            payFullAmount: Boolean?,
            paymentPhaseId: Long?,
            idempotencyKey: String?,
            locale: String?,
            klarna: KlarnaBillingRequest?,
        ): CheckoutSessionDto {
            klarnaSeen += klarna
            calls += listOf(reservationId, payFullAmount, paymentPhaseId, idempotencyKey, locale)
            failWith?.let { throw it }
            return CheckoutSessionDto("tr_7UhSN1zuXS", CheckoutSessionStatusEnum.PAYMENT_PENDING, "https://www.mollie.com/checkout/select-method/7UhSN1zuXS")
        }

        override fun checkStatus(ref: String): CheckoutSessionDto? = known[ref]
    }

    private fun router(
        configured: String,
        properties: MollieProperties = mollieProperties(),
        mollieProvider: CardPaymentProvider = mollie,
    ) = CardPaymentRouter(
        listOf(StripeCardPaymentProvider(stripeService, phases), mollieProvider),
        properties,
        MollieBookingProperties(Duration.ofMinutes(15)),
        configured,
    )

    private fun mvc(router: CardPaymentRouter): MockMvc =
        MockMvcBuilders
            .standaloneSetup(CardPaymentController(router), PublicCardPaymentController(router))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    private fun MockMvc.create(
        base: String,
        query: String = "paymentPhaseId=7&idempotencyKey=key-1&locale=de",
    ): ResultActionsDsl = post("$base/create-checkout-session/42?$query")

    private fun ResultActionsDsl.expectError(
        httpStatus: Int,
        code: ApiErrorCodes,
    ) = andExpect {
        status { isEqualTo(httpStatus) }
        jsonPath("$.code") { value(code.code) }
        jsonPath("$.message") { value(code.message) }
    }

    @Test
    fun `stripe (default, unknown or blank value) - Stripe session mapped like the old Stripe controllers, locale ignored`() {
        `when`(stripeService.initiatePayment(42, null, 7, "key-1")).thenReturn(stripeSession)
        listOf("stripe", "STRIPE", " Stripe ", "", "mollie-typo").forEach { configured ->
            val router = router(configured)
            router.activeProvider shouldBe CardProvider.STRIPE
            listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
                mvc(router).create(base).andExpect {
                    status { isOk() }
                    jsonPath("$.sessionIdOrOrderCode") { value("cs_test_a1B2c3") }
                    jsonPath("$.status") { value("PAYMENT_PENDING") }
                    jsonPath("$.redirectUrl") { value("https://checkout.stripe.com/c/pay/cs_test_a1B2c3") }
                }
            }
        }
        mollie.calls.size shouldBe 0
    }

    @Test
    fun `stripe - pay full and the selection 400 or unknown reservation 404 pass through unchanged`() {
        `when`(stripeService.initiatePayment(42, true, null, null)).thenReturn(stripeSession)
        mvc(router("stripe")).create("/public/payments/card", "payFullAmount=true").andExpect {
            status { isOk() }
            jsonPath("$.sessionIdOrOrderCode") { value("cs_test_a1B2c3") }
        }

        `when`(stripeService.initiatePayment(42, null, null, null))
            .thenThrow(ParameterValidationException(mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter")))
        mvc(router("stripe")).create("/public/payments/card", "").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value(ApiErrorCodes.INVALID_REQUEST_PARAMETERS.code) }
        }

        `when`(stripeService.initiatePayment(43, false, null, null)).thenThrow(ReservationNotExistException())
        mvc(router("stripe")).post("/public/payments/card/create-checkout-session/43?payFullAmount=false").expectError(404, ApiErrorCodes.RESERVATION_NOT_EXIST)
    }

    @Test
    fun `mollie - new checkouts go to Mollie with every parameter incl locale, Stripe untouched`() {
        val router = router("mollie")
        router.activeProvider shouldBe CardProvider.MOLLIE
        listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
            mvc(router).create(base).andExpect {
                status { isOk() }
                jsonPath("$.sessionIdOrOrderCode") { value("tr_7UhSN1zuXS") }
                jsonPath("$.status") { value("PAYMENT_PENDING") }
                jsonPath("$.redirectUrl") { value("https://www.mollie.com/checkout/select-method/7UhSN1zuXS") }
            }
        }
        mollie.calls shouldBe List(2) { listOf(42L, null, 7L, "key-1", "de") }
        verifyNoInteractions(stripeService)
    }

    @Test
    fun `klarna - the billing address travels in the body to Mollie, a broken one is a 400, Stripe answers 422 (8107)`() {
        val address = """{"klarna":{"givenName":"Hans","familyName":"Mueller","streetAndNumber":"Hellersbergstrasse 14","postalCode":"41460","city":"Neuss","country":"DE"}}"""
        listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
            mvc(router("mollie"))
                .post("$base/create-checkout-session/42?paymentPhaseId=7&idempotencyKey=key-1&locale=de") {
                    contentType = MediaType.APPLICATION_JSON
                    content = address
                }.andExpect { status { isOk() } }
        }
        mollie.klarnaSeen.map { it?.country to it?.city } shouldBe List(2) { "DE" to "Neuss" }
        // No body, an empty body with a JSON content type (what the web sends for cards), or {} = the card checkout as before.
        mvc(router("mollie")).create("/public/payments/card").andExpect { status { isOk() } }
        mollie.klarnaSeen.last() shouldBe null
        listOf("", "{}", """{"klarna":null}""").forEach { cardBody ->
            mvc(router("mollie"))
                .post("/public/payments/card/create-checkout-session/42?paymentPhaseId=7&idempotencyKey=key-1&locale=de") {
                    contentType = MediaType.APPLICATION_JSON
                    content = cardBody
                }.andExpect { status { isOk() } }
            mollie.klarnaSeen.last() shouldBe null
        }

        mvc(router("mollie"))
            .post("/public/payments/card/create-checkout-session/42?paymentPhaseId=7") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"klarna":{"givenName":"Hans","country":"Germany"}}"""
            }.andExpect { status { isBadRequest() } }
        mollie.klarnaSeen.size shouldBe 6

        mvc(router("stripe"))
            .post("/public/payments/card/create-checkout-session/42?paymentPhaseId=7") {
                contentType = MediaType.APPLICATION_JSON
                content = address
            }.expectError(422, ApiErrorCodes.KLARNA_NOT_AVAILABLE)
        verifyNoInteractions(stripeService)
    }

    @Test
    fun `mollie flag without Mollie api key (or a test key under prod) - router starts, every checkout 503 (8101), nobody called`() {
        listOf(mollieProperties(apiKey = ""), mollieProperties(apiKey = TEST_API_KEY, profiles = listOf("prod"))).forEach { disabled ->
            disabled.enabled shouldBe false
            val router = router("mollie", disabled)
            listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
                mvc(router).create(base).expectError(503, ApiErrorCodes.CARD_PAYMENTS_UNAVAILABLE)
            }
        }
        mollie.calls.size shouldBe 0
        verifyNoInteractions(stripeService)
    }

    @Test
    fun `the wired Mollie provider - a reference without a ledger row is unknown (Stripe, then 404), disabled keys 503 before any work`() {
        val orders = mock(ReservationCardOrderRepository::class.java)
        val client = mock(MollieClient::class.java)
        val createTx = mock(MollieBookingCreateTx::class.java)

        fun provider(properties: MollieProperties) =
            MollieBookingPaymentService(
                properties,
                client,
                createTx,
                mock(ReservationCardOrderTx::class.java),
                orders,
                mock(BookingCardCompletionFollowUp::class.java),
                mock(StripeSessionExpirer::class.java),
                mock(BookingCardAlertMailer::class.java),
                "https://www.boat4you.com",
            )
        val enabled = provider(mollieProperties())
        enabled.provider shouldBe CardProvider.MOLLIE
        enabled.checkStatus("tr_7UhSN1zuXS").shouldBeNull()
        verify(orders).findByProviderOrderId("tr_7UhSN1zuXS")
        mvc(router("mollie", mollieProvider = enabled)).post("/public/payments/card/checkPaymentStatus/tr_7UhSN1zuXS").expectError(404, ApiErrorCodes.CARD_PAYMENT_NOT_FOUND)

        val disabled = provider(mollieProperties(apiKey = ""))
        shouldThrow<CardPaymentsUnavailableException> { disabled.createCheckout(42, null, 7, "key-1", "de") }
        mvc(router("mollie", mollieProperties(apiKey = ""), mollieProvider = disabled)).create("/public/payments/card").expectError(503, ApiErrorCodes.CARD_PAYMENTS_UNAVAILABLE)
        verifyNoInteractions(client, createTx, stripeService)
    }

    @Test
    fun `409 bodies - in progress and already paid carry static friendly text, 502 for a provider error, 422 above the card limit`() {
        val mvc = mvc(router("mollie"))
        mollie.failWith = CardPaymentInProgressException()
        mvc.create("/public/payments/card").expectError(409, ApiErrorCodes.CARD_PAYMENT_IN_PROGRESS).andExpect {
            jsonPath("$.code") { value(8102) }
            jsonPath("$.message") {
                value("A card payment for this booking is already being processed. Please wait a few minutes and check your booking before paying again.")
            }
        }
        mollie.failWith = CardAlreadyPaidException()
        mvc.create("/secured/payments/card").expectError(409, ApiErrorCodes.CARD_ALREADY_PAID).andExpect {
            jsonPath("$.code") { value(8103) }
            jsonPath("$.message") { value("This payment has already been received. Please refresh the page to see your booking.") }
        }
        mollie.failWith = CardPaymentProviderException()
        mvc.create("/public/payments/card").expectError(502, ApiErrorCodes.CARD_PAYMENT_PROVIDER_ERROR)
        mollie.failWith = CardAmountAboveLimitException()
        mvc.create("/public/payments/card").expectError(422, ApiErrorCodes.CARD_AMOUNT_ABOVE_LIMIT).andExpect {
            jsonPath("$.code") { value(8106) }
        }
    }

    @Test
    fun `status - Mollie ref, Stripe ref, unknown or absurd ref 404 (whatever the flag says)`() {
        mollie.known["tr_7UhSN1zuXS"] = CheckoutSessionDto("tr_7UhSN1zuXS", CheckoutSessionStatusEnum.PAYMENT_SUCCESS)
        val stripePhase = ReservationPaymentPhase().apply { id = 7 }
        `when`(phases.findByStripeSessionIdOrderByDeadlineAsc("cs_test_a1B2c3")).thenReturn(listOf(stripePhase))
        `when`(stripeService.checkPaymentStatus("cs_test_a1B2c3"))
            .thenReturn(CheckoutSessionDto("cs_test_a1B2c3", CheckoutSessionStatusEnum.PAYMENT_PENDING))

        listOf(router("stripe"), router("mollie"), router("mollie", mollieProperties(apiKey = ""))).forEach { router ->
            listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
                mvc(router).post("$base/checkPaymentStatus/tr_7UhSN1zuXS").andExpect {
                    status { isOk() }
                    jsonPath("$.sessionIdOrOrderCode") { value("tr_7UhSN1zuXS") }
                    jsonPath("$.status") { value("PAYMENT_SUCCESS") }
                }
                mvc(router).post("$base/checkPaymentStatus/cs_test_a1B2c3").andExpect {
                    status { isOk() }
                    jsonPath("$.sessionIdOrOrderCode") { value("cs_test_a1B2c3") }
                    jsonPath("$.status") { value("PAYMENT_PENDING") }
                }
                // Unknown to both: the old Stripe status endpoint would say SUCCESS here; this one says 404.
                mvc(router).post("$base/checkPaymentStatus/cs_test_unknown").expectError(404, ApiErrorCodes.CARD_PAYMENT_NOT_FOUND)
                mvc(router).post("$base/checkPaymentStatus/${"x".repeat(256)}").expectError(404, ApiErrorCodes.CARD_PAYMENT_NOT_FOUND)
            }
        }
        verify(stripeService, never()).checkPaymentStatus("cs_test_unknown")
    }

    private fun captureLogs(
        loggerClass: Class<*>,
        block: () -> Unit,
    ): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(loggerClass) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    /** The seam as Spring builds it in the app (the Stripe adapter over mocks; the rest as declared). */
    private val contextRunner =
        ApplicationContextRunner()
            .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }
            .withBean(StripeCardPaymentProvider::class.java, { StripeCardPaymentProvider(stripeService, phases) })
            // The Mollie provider's collaborators (database + Mollie API) are mocks; the provider itself is the real bean.
            .withBean(MollieClient::class.java, { mock(MollieClient::class.java) })
            .withBean(MollieBookingCreateTx::class.java, { mock(MollieBookingCreateTx::class.java) })
            .withBean(ReservationCardOrderTx::class.java, { mock(ReservationCardOrderTx::class.java) })
            .withBean(ReservationCardOrderRepository::class.java, { mock(ReservationCardOrderRepository::class.java) })
            .withBean(BookingCardCompletionFollowUp::class.java, { mock(BookingCardCompletionFollowUp::class.java) })
            .withBean(StripeSessionExpirer::class.java, { mock(StripeSessionExpirer::class.java) })
            .withBean(BookingCardAlertMailer::class.java, { mock(BookingCardAlertMailer::class.java) })
            .withPropertyValues("server.host-public=https://www.boat4you.com")
            .withUserConfiguration(
                MollieProperties::class.java,
                MollieBookingProperties::class.java,
                MollieBookingPaymentService::class.java,
                CardPaymentRouter::class.java,
            )

    @Test
    fun `spring wiring - default stripe with one INFO line, mollie without a key still starts with an ERROR, no secrets logged`() {
        val defaultLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                    context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.STRIPE
                    context.getBean(MollieBookingProperties::class.java).checkoutTtl shouldBe Duration.ofMinutes(15)
                    context.getBean(MollieProperties::class.java).enabled shouldBe false
                    context.getBean(MollieProperties::class.java).cardMaxAmountMinor shouldBe 1_000_000L
                }
            }
        defaultLogs.filter { it.level == Level.ERROR }.shouldBeEmpty()
        defaultLogs.filter { it.level == Level.INFO } shouldHaveSize 1
        defaultLogs.single { it.level == Level.INFO }.formattedMessage shouldBe
            "Card payments: provider=stripe, mollie-enabled=false, mollie checkout-ttl=PT15M, card-max=1000000 cents"

        val mollieLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues(
                        "application.payment.card-provider=mollie",
                        "application.mollie.booking.checkout-ttl=PT12M",
                    ).run { context ->
                        context.startupFailure.shouldBeNull()
                        context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.MOLLIE
                        context.getBean(MollieBookingProperties::class.java).checkoutTtl shouldBe Duration.ofMinutes(12)
                    }
            }
        mollieLogs.single { it.level == Level.ERROR }.formattedMessage shouldContain "not configured"
        mollieLogs.single { it.level == Level.INFO }.formattedMessage shouldContain "provider=mollie, mollie-enabled=false"

        val enabledLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues(
                        "application.payment.card-provider=mollie",
                        "application.mollie.base-url=https://api.mollie.test",
                        "application.mollie.api-key=$TEST_API_KEY",
                    ).run { context -> context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.MOLLIE }
            }
        enabledLogs.filter { it.level == Level.ERROR }.shouldBeEmpty()
        enabledLogs.single { it.level == Level.INFO }.formattedMessage shouldContain "provider=mollie, mollie-enabled=true"
        val typoLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues("application.payment.card-provider=revolut")
                    .run { context -> context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.STRIPE }
            }
        typoLogs.single { it.level == Level.ERROR }.formattedMessage shouldContain "'revolut' is not stripe|mollie"
        typoLogs.single { it.level == Level.INFO }.formattedMessage shouldContain "provider=stripe"
        val blankLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues("application.payment.card-provider=")
                    .run { context -> context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.STRIPE }
            }
        blankLogs.filter { it.level == Level.ERROR }.shouldBeEmpty()
        (defaultLogs + mollieLogs + enabledLogs).forEach {
            it.formattedMessage shouldNotContain TEST_API_KEY
        }
    }

    @Test
    fun `booking settings - a non-positive checkout TTL falls back to the default with an ERROR`() {
        lateinit var properties: MollieBookingProperties
        val logs = captureLogs(MollieBookingProperties::class.java) { properties = MollieBookingProperties(Duration.ZERO) }
        properties.checkoutTtl shouldBe Duration.ofMinutes(15)
        logs.filter { it.level == Level.ERROR } shouldHaveSize 1
    }

    @Test
    fun `secured controller requires a login, the public one is open (permitAll under public)`() {
        CardPaymentController::class.java.getAnnotation(PreAuthorize::class.java).value shouldBe "isAuthenticated()"
        PublicCardPaymentController::class.java.getAnnotation(PreAuthorize::class.java).shouldBeNull()
    }
}
