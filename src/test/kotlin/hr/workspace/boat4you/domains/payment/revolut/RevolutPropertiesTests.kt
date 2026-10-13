package hr.workspace.boat4you.domains.payment.revolut

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.retry.annotation.EnableRetry
import java.time.Duration

class RevolutPropertiesTests {
    @Configuration
    @EnableRetry
    class RetryConfig

    /** The Revolut beans as Spring builds them in the app: constructor choice, empty handler list, retry proxy. */
    private val contextRunner =
        ApplicationContextRunner()
            .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    HttpMessageConvertersAutoConfiguration::class.java,
                    HttpClientAutoConfiguration::class.java,
                    RestClientAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(
                RetryConfig::class.java,
                RevolutProperties::class.java,
                RevolutMerchantClient::class.java,
                RevolutSignatureVerifier::class.java,
                RevolutWebhookDispatcher::class.java,
                RevolutWebhookController::class.java,
            )

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(RevolutProperties::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    @Test
    fun `enabled only when base-url, secret-key and webhook-secret are all set`() {
        revolutProperties().enabled shouldBe true
        revolutProperties(baseUrl = "").enabled shouldBe false
        revolutProperties(secretKey = "").enabled shouldBe false
        revolutProperties(webhookSecret = "").enabled shouldBe false
        revolutProperties(secretKey = "   ", webhookSecret = "\t").enabled shouldBe false
        revolutProperties(baseUrl = "", secretKey = "", webhookSecret = "").enabled shouldBe false
    }

    @Test
    fun `prod profile with a sandbox base-url is disabled with an ERROR, a production url is fine`() {
        lateinit var props: RevolutProperties
        val logs = captureLogs { props = revolutProperties(profiles = listOf("prod")) }
        props.enabled shouldBe false
        logs.filter { it.level == Level.ERROR } shouldHaveSize 1
        logs.single { it.level == Level.ERROR }.formattedMessage shouldContain "sandbox"

        revolutProperties(baseUrl = "https://SANDBOX-merchant.revolut.com", profiles = listOf("prod")).enabled shouldBe false
        revolutProperties(baseUrl = "https://merchant.revolut.com", profiles = listOf("prod")).enabled shouldBe true
        revolutProperties(profiles = listOf("dev", "data-sync")).enabled shouldBe true
    }

    @Test
    fun `one startup INFO line without secrets`() {
        val logs = captureLogs { revolutProperties(linkKeys = "boat4you=link_key_not_real_123") }
        val info = logs.filter { it.level == Level.INFO }
        info shouldHaveSize 1
        info[0].formattedMessage shouldContain "enabled=true"
        info[0].formattedMessage shouldContain "sandbox-merchant.revolut.com"
        for (event in logs) {
            event.formattedMessage shouldNotContain TEST_SECRET_KEY
            event.formattedMessage shouldNotContain TEST_WEBHOOK_SECRET
            event.formattedMessage shouldNotContain "link_key_not_real_123"
        }

        val missing = captureLogs { revolutProperties(secretKey = "", webhookSecret = "") }.single { it.level == Level.INFO }
        missing.formattedMessage shouldContain "enabled=false"
        missing.formattedMessage shouldContain "secret-key"
        missing.formattedMessage shouldContain "webhook-secret"
    }

    @Test
    fun `values are normalised`() {
        val props =
            revolutProperties(
                baseUrl = " https://merchant.revolut.com/ ",
                checkoutOrigins = "https://checkout.revolut.com/, https://sandbox-checkout.revolut.com ,,",
            )
        props.baseUrl shouldBe "https://merchant.revolut.com"
        props.checkoutOrigins shouldContainExactly listOf("https://checkout.revolut.com", "https://sandbox-checkout.revolut.com")
        props.customPayment.minAmountMinor shouldBe 5000
        props.customPayment.maxAmount shouldBe 100_000
        props.customPayment.maxAmountMinor shouldBe 10_000_000
        props.customPayment.orderTtl shouldBe Duration.ofHours(1)
        props.customPayment.termsVersion shouldBe "2026-10-08"
        props.toString() shouldNotContain TEST_SECRET_KEY
    }

    @Test
    fun `binds application revolut keys and wires the Revolut beans in a Spring context`() {
        contextRunner
            .withPropertyValues(
                "application.revolut.base-url=https://merchant.revolut.com/",
                "application.revolut.secret-key=$TEST_SECRET_KEY",
                "application.revolut.webhook-secret=$TEST_WEBHOOK_SECRET",
                "application.revolut.custom-payment.order-ttl=PT3M",
                "application.revolut.custom-payment.max-amount=2500",
            ).run { context ->
                context.startupFailure shouldBe null
                val props = context.getBean(RevolutProperties::class.java)
                props.enabled shouldBe true
                props.baseUrl shouldBe "https://merchant.revolut.com"
                props.customPayment.orderTtl shouldBe Duration.ofMinutes(3)
                props.customPayment.maxAmountMinor shouldBe 250_000
                props.checkoutOrigins shouldContainExactly listOf("https://checkout.revolut.com")
                AopUtils.isAopProxy(context.getBean(RevolutMerchantClient::class.java)) shouldBe true
                context.getBean(RevolutWebhookDispatcher::class.java).dispatch("ORDER_FAILED", "unknown-order") shouldBe
                    RevolutWebhookDispatcher.DispatchResult.UNKNOWN
            }
    }

    @Test
    fun `a node without any Revolut env var still starts, with the feature disabled`() {
        contextRunner.run { context ->
            context.startupFailure shouldBe null
            val props = context.getBean(RevolutProperties::class.java)
            props.enabled shouldBe false
            props.reconcileEnabled shouldBe false
            props.customPayment.minAmountMinor shouldBe 5000
            props.customPayment.orderTtl shouldBe Duration.ofHours(1)
        }
    }
}
