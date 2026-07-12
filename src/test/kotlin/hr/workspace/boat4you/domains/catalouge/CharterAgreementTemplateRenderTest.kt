package hr.workspace.boat4you.domains.catalouge

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.junit.jupiter.api.Test
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayOutputStream
import kotlin.test.assertTrue

/**
 * Standalone render check for the charter-agreement template's two payment
 * options (Mario 12.7.2026). Renders with the exact variables the service
 * produces for booking 1441002 (2 phases of 2677.60, card 5% / bank 32) and
 * asserts the method-specific instalments + totals appear, and that Thymeleaf
 * processes the template without error. No Spring context / DB needed.
 */
class CharterAgreementTemplateRenderTest {

    private fun engine(): SpringTemplateEngine {
        val resolver = ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
        }
        return SpringTemplateEngine().apply { setTemplateResolver(resolver) }
    }

    @Test
    fun `renders two payment options with method-specific totals`() {
        val vars = mapOf<String, Any?>(
            "reservationNumber" to "1441002/2026",
            "agreementDate" to "12 July 2026",
            "clientFullName" to "Bryan Lacy",
            "clientFirstName" to "Bryan",
            "clientLastName" to "Lacy",
            "clientEmail" to "bryanlacy@gmail.com",
            "clientPhone" to "+14236195718",
            "clientAddress" to "—",
            "clientCity" to "—",
            "clientCountry" to "—",
            "brokerName" to "Cusmanich d.o.o.",
            "brokerTradingAs" to "Boat4You",
            "brokerAddress" to "Vrboran 37, 21000 Split, Croatia",
            "brokerOib" to "HR87394862517",
            "brokerEmail" to "info@boat4you.com",
            "brokerPhone" to "+385 91 3000 009",
            "signatureImageDataUrl" to null,
            "yachtFullLabel" to "Bali Catamarans Bali 4.2 Chèrie",
            "yachtYearBuilt" to "2025",
            "yachtLengthMeters" to "12.85",
            "yachtCabins" to "4",
            "charterType" to "Skippered charter",
            "pickupLocation" to "ACI Marina Dubrovnik",
            "pickupCountry" to "Croatia",
            "countryAdjective" to "Croatian",
            "checkInDate" to "26 September 2026",
            "checkInTime" to "17:00",
            "checkOutDate" to "03 October 2026",
            "checkOutTime" to "09:00",
            "nightsCount" to 7L,
            "currency" to "EUR",
            "currencySymbol" to "€",
            "basePriceLabel" to "5020.00€",
            "discountLabel" to "1204.80€",
            "clientPriceLabel" to "3815.20€",
            "totalPriceLabel" to "5355.20€",
            "hasDiscount" to true,
            "inPaymentExtras" to listOf(mapOf("name" to "Skipper", "priceLabel" to "1540.00€")),
            "hasInPaymentExtras" to true,
            "hasPaymentPhases" to true,
            "bankPhases" to listOf(
                mapOf("label" to "Deposit payment", "amountLabel" to "2693.60€", "deadlineLabel" to "14 July 2026"),
                mapOf("label" to "Final balance payment", "amountLabel" to "2693.60€", "deadlineLabel" to "27 August 2026"),
            ),
            "cardPhases" to listOf(
                mapOf("label" to "Deposit payment", "amountLabel" to "2811.60€", "deadlineLabel" to "14 July 2026"),
                mapOf("label" to "Final balance payment", "amountLabel" to "2811.60€", "deadlineLabel" to "27 August 2026"),
            ),
            "bankTotalLabel" to "5387.20€",
            "cardTotalLabel" to "5623.20€",
            "hasBankFee" to true,
            "hasCardFee" to true,
            "bankFeeLabel" to "32.00€",
            "cardSurchargePercentLabel" to "5%",
            "paymentLink" to "https://www.boat4you.com/my-bookings/19",
            "obligatoryExtras" to listOf(
                mapOf(
                    "name" to "Charter pack",
                    "priceLabel" to "390.00€",
                    "unitLabel" to "Per booking",
                    "payAtMarina" to true,
                    "settlementLabel" to "At marina",
                ),
            ),
            "hasObligatoryExtras" to true,
        )

        val html = engine().process("contract/charterAgreement", Context().apply { setVariables(vars) })

        // Also render the actual PDF (openhtmltopdf) so template errors that
        // only surface at PDF-conversion time (invalid XML, unsupported CSS)
        // are caught here rather than at generation time in production.
        val pdfBytes = ByteArrayOutputStream().use { out ->
            PdfRendererBuilder()
                .useFastMode()
                .withHtmlContent(html, "classpath:/templates/contract/")
                .toStream(out)
                .run()
            out.toByteArray()
        }
        assertTrue(pdfBytes.size > 1000, "PDF render produced no meaningful output")

        // Two options present
        assertTrue(html.contains("Payment options"), "missing 'Payment options' heading")
        assertTrue(html.contains(">Bank transfer"), "missing Bank transfer option")
        assertTrue(html.contains(">Credit card"), "missing Credit card option")
        // Bank transfer instalments + total (base 2677.60 + 16 = 2693.60; total 5387.20)
        assertTrue(html.contains("2693.60€"), "missing bank instalment amount")
        assertTrue(html.contains("5387.20€"), "missing bank total")
        // Credit card instalments + total (base 2677.60 + 134 = 2811.60; total 5623.20)
        assertTrue(html.contains("2811.60€"), "missing card instalment amount")
        assertTrue(html.contains("5623.20€"), "missing card total")
        // Fee disclosures + link
        assertTrue(html.contains("32.00€"), "missing bank fee label")
        assertTrue(html.contains("card-processing fee"), "missing card fee note")
        assertTrue(html.contains("https://www.boat4you.com/my-bookings/19"), "missing payment link")
        // Charter price block still intact
        assertTrue(html.contains("5355.20€"), "missing base charter total")
        assertTrue(html.contains("Skipper"), "missing skipper line")
    }
}
