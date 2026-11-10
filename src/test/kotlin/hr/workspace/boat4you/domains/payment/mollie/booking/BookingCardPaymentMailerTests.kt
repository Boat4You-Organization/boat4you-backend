package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.openapitools.model.LanguageEnum
import org.springframework.context.support.ResourceBundleMessageSource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The two e-mails of an online-booking instalment paid by card: who gets them, what they say, and that both templates
 * render with the real message bundles in every client language (a missing key prints ??key??).
 */
class BookingCardPaymentMailerTests {
    private val messageSource =
        ResourceBundleMessageSource().apply {
            setBasename("messages/email")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
        }

    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    templateMode = TemplateMode.HTML
                    characterEncoding = "UTF-8"
                },
            )
            setTemplateEngineMessageSource(messageSource)
        }

    private fun notice(
        language: LanguageEnum? = LanguageEnum.DE,
        email: String? = "client@example.com",
    ) = BookingPaymentNotice(
        ledgerId = 7,
        reservationId = 37,
        reference = "1001112/2026",
        amountMinor = 123_225,
        baseAmount = BigDecimal("1173.25"),
        surchargeAmount = BigDecimal("59"),
        providerOrderId = "tr_ifDA6Toe8higiBbZjMNZJ",
        checkoutToken = UUID.fromString("840c93d0-278a-40e3-95d0-9be3c73e77e2"),
        completedAt = Instant.parse("2026-11-10T13:55:00Z"),
        payerEmail = email,
        language = language,
    )

    private data class Sent(
        val recipients: List<String>,
        val subject: String,
        val template: String,
        val variables: Map<String, Any?>,
        val replyTo: String?,
        val locale: Locale?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun send(
        notice: BookingPaymentNotice,
        admins: List<String> = listOf("office@boat4you.com", "mario@boat4you.com"),
    ): List<Sent> {
        val emailService = mock(EmailService::class.java)
        val users = mock(UserRepository::class.java)
        `when`(users.findAllAdminEmailAddresses()).thenReturn(admins)
        BookingCardPaymentMailer(emailService, users, messageSource, "https://admin.boat4you.com/", "https://www.boat4you.com/")
            .paymentRecorded(notice, "Visa •••• 9996")
        return mockingDetails(emailService).invocations.filter { it.method.name == "sendEmail" }.map { call ->
            val a = call.arguments
            Sent(
                recipients = a[0] as List<String>,
                subject = a[1] as String,
                template = a[2] as String,
                variables = a[3] as Map<String, Any?>,
                replyTo = a.filterIsInstance<String>().firstOrNull { it == notice.payerEmail && it != a[1] },
                locale = a.filterIsInstance<Locale>().firstOrNull(),
            )
        }
    }

    private fun render(mail: Sent): String = engine.process(mail.template, Context(mail.locale ?: Locale.ENGLISH).apply { setVariables(mail.variables) })

    @Test
    fun `office gets Card payment received with the booking, the amounts and a link, the client the payment confirmation`() {
        val sent = send(notice())
        sent shouldHaveSize 2

        val office = sent.single { it.template == "email/customPaymentAdmin" }
        office.recipients shouldContainExactly listOf("office@boat4you.com", "mario@boat4you.com")
        office.subject shouldBe "Card payment received: 1232.25€ · booking 1001112/2026 · Boat4You"
        office.replyTo shouldBe "client@example.com"
        val officeHtml = render(office)
        officeHtml shouldNotContain "??"
        officeHtml shouldContain "online booking 1001112/2026"
        officeHtml shouldContain "marked as paid"
        officeHtml shouldContain "instalment 1173.25€ + card surcharge 59.00€"
        officeHtml shouldContain "https://admin.boat4you.com/bookings/37"
        officeHtml shouldContain "tr_ifDA6Toe8higiBbZjMNZJ"
        officeHtml shouldContain "Visa •••• 9996"
        officeHtml shouldNotContain "secret /pay link"
        officeHtml shouldNotContain "Custom payments"

        val receipt = sent.single { it.template == "email/customPaymentReceipt" }
        receipt.recipients shouldContainExactly listOf("client@example.com")
        receipt.locale shouldBe Locale.GERMAN
        receipt.subject shouldContain "1001112/2026"
        receipt.subject shouldContain "Boat4You"
        val receiptHtml = render(receipt)
        receiptHtml shouldNotContain "??"
        receiptHtml shouldContain "1232.25€"
        receiptHtml shouldContain "1001112/2026"
        receiptHtml shouldContain "Visa •••• 9996"
    }

    @Test
    fun `the receipt renders in every client language, English when the language is unknown`() {
        (LanguageEnum.entries.map { it as LanguageEnum? } + null).forEach { language ->
            val receipt = send(notice(language = language)).single { it.template == "email/customPaymentReceipt" }
            val html = render(receipt)
            html shouldNotContain "??"
            html shouldContain "1001112/2026"
            if (language == null) receipt.locale shouldBe Locale.ENGLISH
        }
    }

    @Test
    fun `no client e-mail address - only the office is told, no admin address - only the client`() {
        send(notice(email = null)).map { it.template } shouldContainExactly listOf("email/customPaymentAdmin")
        send(notice(), admins = emptyList()).map { it.template } shouldContainExactly listOf("email/customPaymentReceipt")
    }
}
