package hr.workspace.boat4you.domains.review

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.review.service.ReviewEmailSender
import hr.workspace.boat4you.domains.review.service.ReviewLinks
import hr.workspace.boat4you.domains.review.service.ReviewReservationContext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.support.ResourceBundleMessageSource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDateTime
import java.util.Locale

/**
 * Renders both review request e-mails in every language with the real message bundles (same basename / encoding
 * as spring.messages): catches a missing key (Thymeleaf prints ??key_locale??), a broken expression, or an
 * apostrophe that MessageFormat swallowed.
 */
class ReviewEmailTemplateRenderTests {
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

    private val sender =
        ReviewEmailSender(mock(EmailService::class.java), messageSource, "https://api.boat4you.com", "https://www.boat4you.com")

    private val context =
        ReviewReservationContext(
            reservationId = 4242,
            reservationNumber = "100194/2026",
            dateFrom = LocalDateTime.parse("2026-07-04T17:00:00"),
            dateTo = LocalDateTime.parse("2026-07-11T09:00:00"),
            yachtId = 77,
            yachtName = "Tria",
            modelName = "Oceanis 46.1",
            manufacturerName = "Beneteau",
            yachtMainImageId = 555,
            baseName = "ACI Marina Split",
            baseCountry = "Croatia",
            flowEmail = "guest@example.com",
            flowName = "Ana",
            flowSurname = "Horvat",
            userId = 9,
            userName = "Ana",
            userSurname = "Horvat",
            userLanguage = "DE",
            userCountry = "Germany",
        )

    @Test
    fun `both templates render in every language with every key resolved`() {
        for (kind in ReviewKind.entries) {
            for (lc in ReviewLinks.SUPPORTED_LOCALES) {
                val url = ReviewLinks.reviewUrl("https://www.boat4you.com", lc, "TOKEN")
                val html =
                    engine.process(
                        if (kind == ReviewKind.BOOKING) ReviewEmailSender.BOOKING_TEMPLATE else ReviewEmailSender.YACHT_TEMPLATE,
                        Context(Locale.forLanguageTag(lc)).apply { setVariables(sender.variables(kind, context, lc, url)) },
                    )
                html shouldNotContain "??"
                html shouldContain "Ana Horvat"
                html shouldContain "Beneteau Oceanis 46.1 Tria"
                html shouldContain "ACI Marina Split, Croatia"
                html shouldContain "href=\"$url\""
                (1..5).forEach { html shouldContain "href=\"$url?rating=$it\"" }
                html shouldContain "https://api.boat4you.com/public/image/555?width=936"
                html shouldContain "60"
            }
        }
    }

    @Test
    fun `subjects resolve in every language with their argument and apostrophes intact`() {
        for (lc in ReviewLinks.SUPPORTED_LOCALES) {
            val locale = Locale.forLanguageTag(lc)
            messageSource.getMessage("reviewBooking.subject", arrayOf<Any>("100194/2026"), locale) shouldContain "#100194/2026"
            messageSource.getMessage("reviewYacht.subject", arrayOf<Any>("Beneteau Oceanis 46.1 Tria"), locale) shouldContain
                "Beneteau Oceanis 46.1 Tria"
        }
        messageSource.getMessage("reviewBooking.subject", arrayOf<Any>("1"), Locale.FRENCH) shouldBe
            "Comment s'est passée votre réservation avec Boat4You ? ⭐ #1"
        messageSource.getMessage("reviewRequest.duration", arrayOf<Any>(60), Locale.ITALIAN) shouldContain "l'invio"
    }

    @Test
    fun `dates are written in the customer's language`() {
        sender.variables(ReviewKind.YACHT, context, "de", "u")["charterDates"] shouldBe "4 Juli 2026 – 11 Juli 2026"
        sender.variables(ReviewKind.YACHT, context, "en", "u")["charterDates"] shouldBe "4 July 2026 – 11 July 2026"
    }
}
