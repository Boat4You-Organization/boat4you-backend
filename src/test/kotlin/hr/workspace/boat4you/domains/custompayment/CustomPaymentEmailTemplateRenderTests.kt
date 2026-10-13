package hr.workspace.boat4you.domains.custompayment

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentAlertKind
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentMail
import hr.workspace.boat4you.domains.custompayment.service.EmailCustomPaymentMailer
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.core.io.Resource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Renders the custom payment e-mails with the real message bundles (same basename / encoding as spring.messages) for
 * every site and every language of that site: catches a missing key (Thymeleaf prints ??key_locale??), a broken
 * expression, an apostrophe MessageFormat swallowed, a forbidden "invoice" word, or the Boat4You brand leaking into a
 * sister site's receipt.
 */
class CustomPaymentEmailTemplateRenderTests {
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

    private val emailService: EmailService = mock(EmailService::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)

    private val mailer =
        EmailCustomPaymentMailer(
            emailService,
            userRepository,
            messageSource,
            "https://admin.boat4you.com/",
            "Boat4You | Endless blue. Boats 4 you <no-reply@boat4you.com>",
        )

    private val token = UUID.fromString("3f2b8c1e-5d4a-4e1b-9f3c-2a7d6e8b9c01")

    private fun payment(
        site: String = "boat4you",
        locale: String = "en",
    ) = CustomPayment().apply {
        id = 42
        publicToken = token
        this.site = site
        reference = "100116/2027"
        amountMinor = 250_000
        currency = "EUR"
        providerOrderId = "6516e3c4-aaaa-bbbb-cccc-0123456789ab"
        providerPaymentId = "pay-2"
        status = CustomPaymentStatus.COMPLETED
        payerEmail = "client@example.com"
        this.locale = locale
        createdAt = Instant.parse("2026-10-13T13:05:00Z")
        completedAt = Instant.parse("2026-10-13T13:20:00Z")
        // A declined Mastercard first, then the Visa that paid: the receipt must show the paying card.
        orderSnapshot =
            """{"id":"6516e3c4","type":"payment","state":"completed","amount":250000,"currency":"EUR","outstanding_amount":0,
               "refunded_amount":0,"created_at":"2026-10-13T13:05:00Z","updated_at":"2026-10-13T13:20:00Z","payments":[
               {"id":"pay-1","state":"declined","created_at":"2026-10-13T13:10:00Z","card_brand":"MASTERCARD","card_last_four":"1111"},
               {"id":"pay-2","state":"captured","created_at":"2026-10-13T13:19:00Z","card_brand":"VISA","card_last_four":"4242"}]}"""
    }

    private fun render(mail: CustomPaymentMail): String = engine.process(mail.templateName, Context(mail.locale ?: Locale.ENGLISH).apply { setVariables(mail.variables) })

    private fun String.shouldHaveNoInvoiceWords() {
        val lower = lowercase()
        FORBIDDEN.forEach { lower shouldNotContain it }
    }

    @Test
    fun `receipt renders for every site in every language with every key resolved and no invoice wording`() {
        for (site in CustomPaymentSites.ALL) {
            for (lc in site.locales) {
                val mail = mailer.receiptMail(payment(site.key, lc))
                val html = render(mail)

                html shouldNotContain "??"
                html.shouldHaveNoInvoiceWords()
                mail.subject.shouldHaveNoInvoiceWords()
                mail.locale shouldBe Locale.forLanguageTag(lc)
                mail.recipients shouldContainExactly listOf("client@example.com")
                mail.templateName shouldBe EmailCustomPaymentMailer.RECEIPT_TEMPLATE

                mail.subject shouldContain site.brandName
                mail.subject shouldContain "100116/2027"
                mail.subject shouldContain "Cusmanich d.o.o."
                html shouldContain "Cusmanich d.o.o."
                html shouldContain "100116/2027"
                html shouldContain "2500.00€"
                html shouldContain token.toString()
                html shouldContain "Visa •••• 4242"
                html shouldNotContain "1111"
                html shouldContain "(GMT+2)"
                html shouldContain "href=\"${site.baseUrl}\""
                html shouldContain "lang=\"$lc\""
                // Internal template notes are parser-level comments: they never reach the inbox.
                html shouldNotContain "/pay/"

                if (site.key == "boat4you") {
                    html shouldContain "src=\"cid:boat4youLogoFull\""
                    mail.inlineBoat4YouLogo shouldBe true
                    mail.fromOverride shouldBe null
                } else {
                    // Brand line instead of the logo; nothing of Boat4You in a sister site's receipt.
                    html shouldContain ">${site.brandName}</div>"
                    html shouldNotContain "cid:"
                    html.lowercase() shouldNotContain "boat4you"
                    mail.subject.lowercase() shouldNotContain "boat4you"
                    mail.inlineBoat4YouLogo shouldBe false
                    mail.fromOverride shouldBe "${site.brandName} <no-reply@boat4you.com>"
                }
            }
        }
    }

    @Test
    fun `receipt texts - subject, date and amount in the payer's language`() {
        mailer.receiptMail(payment("catamaran-croatia-charter", "en")).subject shouldBe
            "Payment confirmation – 100116/2027 – Catamaran Charter Croatia (Cusmanich d.o.o.)"
        val de = mailer.receiptMail(payment("boat4you", "de"))
        de.subject shouldBe "Zahlungsbestätigung – 100116/2027 – Boat4You (Cusmanich d.o.o.)"
        de.variables["paidAtLabel"] shouldBe "13. Oktober 2026 · 15:20 (GMT+2)"
        render(de) shouldContain "Bezahlt am 13. Oktober 2026 · 15:20 (GMT+2)"
        render(mailer.receiptMail(payment("catamaran-charter-greece", "fr"))) shouldContain
            "Cet e-mail confirme votre paiement par carte à Catamaran Charter Greece (Cusmanich d.o.o.)."
        render(mailer.receiptMail(payment("boat4you", "hr"))) shouldContain "Plaćeno 13. listopada 2026. · 15:20 (GMT+2)"

        EmailCustomPaymentMailer.amountLabel(5000, "EUR") shouldBe "50.00€"
        EmailCustomPaymentMailer.amountLabel(1_000_001, "EUR") shouldBe "10000.01€"
        EmailCustomPaymentMailer.receiptDateLabel(Instant.parse("2026-01-05T08:00:00Z"), Locale.ENGLISH) shouldBe
            "January 5, 2026 · 09:00 (GMT+1)"
        EmailCustomPaymentMailer.adminDateLabel(Instant.parse("2026-10-13T13:20:00Z")) shouldBe "Oct 13, 2026 · 15:20 (GMT+2)"
    }

    @Test
    fun `receipt language - an unknown row locale falls back to English, a missing card hides the card line`() {
        val row = payment("boat4you", "xx").apply { orderSnapshot = null }
        val mail = mailer.receiptMail(row)
        mail.locale shouldBe Locale.ENGLISH
        val html = render(mail)
        html shouldNotContain "??"
        html shouldContain "Payment confirmation"
        html shouldNotContain "••••"
        mail.variables["cardLabel"] shouldBe null
    }

    @Test
    fun `office notification - plain English, no inline images, reply goes to the payer, admin link filtered by reference`() {
        val admins = listOf("office@boat4you.com", "mario@boat4you.com")
        val mail = mailer.adminMail(payment("europe-yachts", "de").apply { reservationId = 777 }, admins)
        val html = render(mail)

        mail.templateName shouldBe EmailCustomPaymentMailer.ADMIN_TEMPLATE
        mail.recipients shouldContainExactly admins
        mail.replyTo shouldBe "client@example.com"
        mail.locale shouldBe null
        mail.inlineBoat4YouLogo shouldBe false
        mail.subject shouldBe "Card payment received: 2500.00€ · 100116/2027 · Europe Yachts Charter"
        html shouldNotContain "??"
        html shouldNotContain "cid:"
        html shouldContain "Card payment received"
        html shouldContain "2500.00€"
        html shouldContain "100116/2027"
        html shouldContain "href=\"mailto:client@example.com\""
        html shouldContain "Oct 13, 2026 · 15:20 (GMT+2)"
        html shouldContain "Visa •••• 4242"
        html shouldContain "6516e3c4-aaaa-bbbb-cccc-0123456789ab"
        html shouldContain "href=\"https://admin.boat4you.com/custom-payments?q=100116%2F2027\""
        html shouldContain "href=\"https://admin.boat4you.com/bookings/777\""

        render(mailer.adminMail(payment(), admins)) shouldNotContain "/bookings/"
    }

    @Test
    fun `alerts - plain admin report mail, one per call (no throttle), no Reply-To`() {
        val admins = listOf("office@boat4you.com")
        `when`(userRepository.findAllAdminEmailAddresses()).thenReturn(admins)
        val row = payment("catamaran-charter-caribbean")

        for (kind in CustomPaymentAlertKind.entries) {
            val mail = mailer.alertMail(row, kind, "amount 1 instead of 250000\r\nsecond line", admins)
            val html = render(mail)
            mail.templateName shouldBe "email/adminBookingFailed"
            mail.replyTo shouldBe null
            mail.subject shouldStartWith "⚠️ Custom payment"
            mail.subject shouldContain "100116/2027"
            mail.subject shouldNotContain "\n"
            html shouldNotContain "??"
            html shouldContain "Reason:     amount 1 instead of 250000 second line"
            html shouldContain "https://admin.boat4you.com/custom-payments?q=100116%2F2027"
            html shouldContain "Catamaran Charter Caribbean (catamaran-charter-caribbean)"
        }

        mailer.alert(row, CustomPaymentAlertKind.REFUND, "Refunded 100 of 250000 cents")
        mailer.alert(row, CustomPaymentAlertKind.REFUND, "Refunded 200 of 250000 cents")
        sendEmailCalls().size shouldBe 2
    }

    @Test
    fun `send - receipt goes through EmailService with the locale, the per-site From and the logo only for boat4you`() {
        mailer.receipt(payment("boat4you", "pl"))
        mailer.receipt(payment("croatia-yachting", "it"))
        val (boat4you, sister) = sendEmailCalls()

        boat4you[0] shouldBe listOf("client@example.com")
        boat4you[2] shouldBe EmailCustomPaymentMailer.RECEIPT_TEMPLATE
        boat4you[5] shouldBe null // replyTo: EmailService default (office mailbox)
        (boat4you[7] as Map<*, *>).keys shouldBe setOf("boat4youLogoFull")
        ((boat4you[7] as Map<*, *>)["boat4youLogoFull"] as Resource).exists() shouldBe true
        boat4you[8] shouldBe null
        boat4you[9] shouldBe Locale.forLanguageTag("pl")

        sister[7] shouldBe emptyMap<String, Resource>()
        sister[8] shouldBe "Yacht Charter Croatia <no-reply@boat4you.com>"
        sister[9] shouldBe Locale.ITALIAN
    }

    @Test
    fun `send - office mails are skipped when there is no admin address`() {
        `when`(userRepository.findAllAdminEmailAddresses()).thenReturn(emptyList())
        mailer.adminNotification(payment())
        mailer.alert(payment(), CustomPaymentAlertKind.STALE, "Still pending")
        sendEmailCalls().size shouldBe 0

        `when`(userRepository.findAllAdminEmailAddresses()).thenReturn(listOf("office@boat4you.com"))
        mailer.adminNotification(payment())
        val call = sendEmailCalls().single()
        call[2] shouldBe EmailCustomPaymentMailer.ADMIN_TEMPLATE
        call[5] shouldBe "client@example.com"
        call[7] shouldBe emptyMap<String, Resource>()
    }

    /** Arguments of every EmailService.sendEmail call, in declaration order (0 recipients … 9 locale). */
    private fun sendEmailCalls(): List<Array<Any?>> =
        mockingDetails(emailService)
            .invocations
            .filter { it.method.name == "sendEmail" }
            .map { it.arguments }

    private companion object {
        val FORBIDDEN = listOf("invoice", "racun", "račun", "rechnung", "factura", "fattura", "factuur", "faktura", "fatura")
    }
}
