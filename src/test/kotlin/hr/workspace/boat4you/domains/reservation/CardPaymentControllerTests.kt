package hr.workspace.boat4you.domains.reservation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.stripe.model.checkout.Session
import hr.workspace.boat4you.common.errorhandling.ApiErrorCodes
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.payment.revolut.RevolutProperties
import hr.workspace.boat4you.domains.payment.revolut.TEST_SECRET_KEY
import hr.workspace.boat4you.domains.payment.revolut.booking.RevolutBookingPaymentService
import hr.workspace.boat4you.domains.payment.revolut.booking.RevolutBookingProperties
import hr.workspace.boat4you.domains.payment.revolut.revolutProperties
import hr.workspace.boat4you.domains.reservation.controllers.CardPaymentController
import hr.workspace.boat4you.domains.reservation.controllers.PublicCardPaymentController
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.service.StripePaymentService
import hr.workspace.boat4you.domains.reservation.service.payment.CardAlreadyPaidException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentInProgressException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProvider
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentRouter
import hr.workspace.boat4you.domains.reservation.service.payment.CardProvider
import hr.workspace.boat4you.domains.reservation.service.payment.StripeCardPaymentProvider
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
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration

/**
 * HTTP contract of the provider-neutral `/secured|public/payments/card` endpoints: provider routing by
 * `application.payment.card-provider`, the Stripe mapping (= the old Stripe controllers), 404 / 409 / 502 / 503
 * bodies through ApiErrorHandler. Stripe and the database are mocked; Revolut is a fake provider.
 */
class CardPaymentControllerTests {
    private val stripeService: StripePaymentService = mock(StripePaymentService::class.java)
    private val phases: ReservationPaymentPhaseRepository = mock(ReservationPaymentPhaseRepository::class.java)
    private val revolut = FakeRevolutProvider()
    private val stripeSession =
        Session().apply {
            id = "cs_test_a1B2c3"
            url = "https://checkout.stripe.com/c/pay/cs_test_a1B2c3"
        }

    private class FakeRevolutProvider : CardPaymentProvider {
        override val provider = CardProvider.REVOLUT
        var failWith: RuntimeException? = null
        val calls = mutableListOf<List<Any?>>()
        val known = mutableMapOf<String, CheckoutSessionDto>()

        override fun createCheckout(
            reservationId: Long,
            payFullAmount: Boolean?,
            paymentPhaseId: Long?,
            idempotencyKey: String?,
            locale: String?,
        ): CheckoutSessionDto {
            calls += listOf(reservationId, payFullAmount, paymentPhaseId, idempotencyKey, locale)
            failWith?.let { throw it }
            return CheckoutSessionDto("ord-7f3a", CheckoutSessionStatusEnum.PAYMENT_PENDING, "https://checkout.revolut.com/payment-link/ord-7f3a")
        }

        override fun checkStatus(ref: String): CheckoutSessionDto? = known[ref]
    }

    private fun router(
        configured: String,
        properties: RevolutProperties = revolutProperties(),
        revolutProvider: CardPaymentProvider = revolut,
    ) = CardPaymentRouter(
        listOf(StripeCardPaymentProvider(stripeService, phases), revolutProvider),
        properties,
        RevolutBookingProperties(Duration.ofHours(1), "automatic"),
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
        listOf("stripe", "STRIPE", " Stripe ", "", "revoult").forEach { configured ->
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
        revolut.calls.size shouldBe 0
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
    fun `revolut - new checkouts go to Revolut with every parameter incl locale, Stripe untouched`() {
        val router = router("revolut")
        router.activeProvider shouldBe CardProvider.REVOLUT
        listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
            mvc(router).create(base).andExpect {
                status { isOk() }
                jsonPath("$.sessionIdOrOrderCode") { value("ord-7f3a") }
                jsonPath("$.status") { value("PAYMENT_PENDING") }
                jsonPath("$.redirectUrl") { value("https://checkout.revolut.com/payment-link/ord-7f3a") }
            }
        }
        revolut.calls shouldBe List(2) { listOf(42L, null, 7L, "key-1", "de") }
        verifyNoInteractions(stripeService)
    }

    @Test
    fun `revolut flag without Revolut keys - router starts, every checkout 503 (8101), nobody called`() {
        listOf(revolutProperties(secretKey = ""), revolutProperties(profiles = listOf("prod"))).forEach { disabled ->
            disabled.enabled shouldBe false
            val router = router("revolut", disabled)
            listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
                mvc(router).create(base).expectError(503, ApiErrorCodes.CARD_PAYMENTS_UNAVAILABLE)
            }
        }
        revolut.calls.size shouldBe 0
        verifyNoInteractions(stripeService)
    }

    @Test
    fun `stage A - the wired Revolut provider (stub) answers 503 and knows no reference`() {
        val stub = RevolutBookingPaymentService()
        stub.provider shouldBe CardProvider.REVOLUT
        stub.checkStatus("ord-7f3a").shouldBeNull()
        mvc(router("revolut", revolutProvider = stub)).create("/public/payments/card").expectError(503, ApiErrorCodes.CARD_PAYMENTS_UNAVAILABLE)
        verifyNoInteractions(stripeService)
    }

    @Test
    fun `409 bodies - in progress and already paid carry static friendly text, 502 for a provider error`() {
        val mvc = mvc(router("revolut"))
        revolut.failWith = CardPaymentInProgressException()
        mvc.create("/public/payments/card").expectError(409, ApiErrorCodes.CARD_PAYMENT_IN_PROGRESS).andExpect {
            jsonPath("$.code") { value(8102) }
            jsonPath("$.message") {
                value("A card payment for this booking is already being processed. Please wait a few minutes and check your booking before paying again.")
            }
        }
        revolut.failWith = CardAlreadyPaidException()
        mvc.create("/secured/payments/card").expectError(409, ApiErrorCodes.CARD_ALREADY_PAID).andExpect {
            jsonPath("$.code") { value(8103) }
            jsonPath("$.message") { value("This payment has already been received. Please refresh the page to see your booking.") }
        }
        revolut.failWith = CardPaymentProviderException()
        mvc.create("/public/payments/card").expectError(502, ApiErrorCodes.CARD_PAYMENT_PROVIDER_ERROR)
    }

    @Test
    fun `status - Revolut ref, Stripe ref, unknown or absurd ref 404 (whatever the flag says)`() {
        revolut.known["ord-7f3a"] = CheckoutSessionDto("ord-7f3a", CheckoutSessionStatusEnum.PAYMENT_SUCCESS)
        val stripePhase = ReservationPaymentPhase().apply { id = 7 }
        `when`(phases.findByStripeSessionIdOrderByDeadlineAsc("cs_test_a1B2c3")).thenReturn(listOf(stripePhase))
        `when`(stripeService.checkPaymentStatus("cs_test_a1B2c3"))
            .thenReturn(CheckoutSessionDto("cs_test_a1B2c3", CheckoutSessionStatusEnum.PAYMENT_PENDING))

        listOf(router("stripe"), router("revolut"), router("revolut", revolutProperties(secretKey = ""))).forEach { router ->
            listOf("/public/payments/card", "/secured/payments/card").forEach { base ->
                mvc(router).post("$base/checkPaymentStatus/ord-7f3a").andExpect {
                    status { isOk() }
                    jsonPath("$.sessionIdOrOrderCode") { value("ord-7f3a") }
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
            .withUserConfiguration(
                RevolutProperties::class.java,
                RevolutBookingProperties::class.java,
                RevolutBookingPaymentService::class.java,
                CardPaymentRouter::class.java,
            )

    @Test
    fun `spring wiring - default stripe with one INFO line, revolut without keys still starts with an ERROR, no secrets logged`() {
        val defaultLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                    context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.STRIPE
                    context.getBean(RevolutBookingProperties::class.java).orderTtl shouldBe Duration.ofHours(1)
                    context.getBean(RevolutBookingProperties::class.java).enforceChallenge shouldBe "automatic"
                }
            }
        defaultLogs.filter { it.level == Level.ERROR }.shouldBeEmpty()
        defaultLogs.filter { it.level == Level.INFO } shouldHaveSize 1
        defaultLogs.single { it.level == Level.INFO }.formattedMessage shouldBe
            "Card payments: provider=stripe, revolut-enabled=false, revolut-booking order-ttl=PT1H, enforce-challenge=automatic"

        val revolutLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues(
                        "application.payment.card-provider=revolut",
                        "application.revolut.booking.order-ttl=PT3M",
                        "application.revolut.booking.enforce-challenge=FORCED",
                    ).run { context ->
                        context.startupFailure.shouldBeNull()
                        context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.REVOLUT
                        context.getBean(RevolutBookingProperties::class.java).orderTtl shouldBe Duration.ofMinutes(3)
                        context.getBean(RevolutBookingProperties::class.java).enforceChallenge shouldBe "forced"
                    }
            }
        revolutLogs.single { it.level == Level.ERROR }.formattedMessage shouldContain "not configured"
        revolutLogs.single { it.level == Level.INFO }.formattedMessage shouldContain "provider=revolut, revolut-enabled=false"

        val enabledLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues(
                        "application.payment.card-provider=revolut",
                        "application.revolut.base-url=https://sandbox-merchant.revolut.com",
                        "application.revolut.secret-key=$TEST_SECRET_KEY",
                        "application.revolut.webhook-secret=wsk_test_not_a_real_secret",
                    ).run { context -> context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.REVOLUT }
            }
        enabledLogs.filter { it.level == Level.ERROR }.shouldBeEmpty()
        enabledLogs.single { it.level == Level.INFO }.formattedMessage shouldContain "provider=revolut, revolut-enabled=true"
        val typoLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues("application.payment.card-provider=revoult")
                    .run { context -> context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.STRIPE }
            }
        typoLogs.single { it.level == Level.ERROR }.formattedMessage shouldContain "'revoult' is not stripe|revolut"
        typoLogs.single { it.level == Level.INFO }.formattedMessage shouldContain "provider=stripe"
        val blankLogs =
            captureLogs(CardPaymentRouter::class.java) {
                contextRunner
                    .withPropertyValues("application.payment.card-provider=")
                    .run { context -> context.getBean(CardPaymentRouter::class.java).activeProvider shouldBe CardProvider.STRIPE }
            }
        blankLogs.filter { it.level == Level.ERROR }.shouldBeEmpty()
        (defaultLogs + revolutLogs + enabledLogs).forEach {
            it.formattedMessage shouldNotContain TEST_SECRET_KEY
            it.formattedMessage shouldNotContain "wsk_"
        }
    }

    @Test
    fun `booking settings - zero TTL or unknown enforce-challenge fall back to the defaults with an ERROR`() {
        lateinit var properties: RevolutBookingProperties
        val logs = captureLogs(RevolutBookingProperties::class.java) { properties = RevolutBookingProperties(Duration.ZERO, "sometimes") }
        properties.orderTtl shouldBe Duration.ofHours(1)
        properties.enforceChallenge shouldBe "automatic"
        logs.filter { it.level == Level.ERROR } shouldHaveSize 2
    }

    @Test
    fun `secured controller requires a login, the public one is open (permitAll under public)`() {
        CardPaymentController::class.java.getAnnotation(PreAuthorize::class.java).value shouldBe "isAuthenticated()"
        PublicCardPaymentController::class.java.getAnnotation(PreAuthorize::class.java).shouldBeNull()
    }
}
