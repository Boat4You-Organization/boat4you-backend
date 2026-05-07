package hr.workspace.boat4you.domains.catalouge.services

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Currency
import java.util.Locale

/**
 * Generates a per-reservation charter agreement PDF that gets attached to
 * the reservation confirmation email (replacing the legacy static T&C PDF).
 *
 * Page 1 = booking data (parties, yacht, dates, pricing, payment schedule,
 * bank transfer details). Page 2+ = full T&C with country-specific
 * jurisdiction text. English-only per Mario's instruction — no
 * localisation. Output is rendered via Thymeleaf to XHTML, then converted
 * to A4 PDF via openhtmltopdf.
 */
@Service
class CharterAgreementService(
    private val templateEngine: SpringTemplateEngine,
    @Value("classpath:data/images/mario-kuzmanic-signature.png")
    private val signatureImage: Resource,
) {
    private val logger = LoggerFactory.getLogger(this::class.java.name)

    /** Base64 data URL of Mario's signature. Read once on first access and
     *  reused for every rendered agreement — the resource is bundled in
     *  the WAR so reloading on each call would just be wasted I/O.
     *  openhtmltopdf can't resolve `classpath:` URIs natively for `<img>`
     *  tags, so embedding as `data:image/png;base64,...` is the simplest
     *  reliable path. */
    private val signatureDataUrl: String by lazy {
        val bytes = signatureImage.inputStream.use { it.readBytes() }
        "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    /**
     * Render the charter agreement for [reservation] to a PDF byte array.
     * The result is attached as `charter-agreement-<ref>.pdf` to the
     * confirmation email.
     */
    fun renderToPdf(reservation: Reservation): ByteArray {
        val variables = buildVariables(reservation)
        val context = Context().apply { setVariables(variables) }
        val html = templateEngine.process(TEMPLATE_NAME, context)

        ByteArrayOutputStream().use { out ->
            PdfRendererBuilder()
                .useFastMode()
                .withHtmlContent(html, BASE_URI)
                .toStream(out)
                .run()
            return out.toByteArray()
        }
    }

    private fun buildVariables(reservation: Reservation): Map<String, Any?> {
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val user = flow.user!!

        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"
        val agreementDate = LocalDate.now().format(DATE_FORMATTER)

        // Charterer (klijent)
        val clientFirstName = user.name
        val clientLastName = user.surname
        val clientFullName = listOf(clientFirstName, clientLastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { flow.getFullName() }
        val clientEmail = user.email
        val clientPhone = user.phoneNumber ?: flow.phoneNumber ?: EM_DASH
        val clientAddress = user.address?.takeIf { it.isNotBlank() } ?: EM_DASH
        val clientCity = user.city?.takeIf { it.isNotBlank() } ?: EM_DASH
        val clientCountry = user.country?.takeIf { it.isNotBlank() } ?: EM_DASH

        // Yacht — Mario rule: Manufacturer + Model + Name.
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModelName = yacht.model?.name?.takeIf { it.isNotBlank() }
        val yachtName = yacht.name?.takeIf { it.isNotBlank() }
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModelName, yachtName)
            .joinToString(" ")
            .ifBlank { yachtName ?: EM_DASH }

        val yachtYearBuilt = yacht.buildYear?.toString() ?: EM_DASH
        val yachtLengthMeters = yacht.length?.toPlainString() ?: EM_DASH
        val yachtCabins = yacht.cabins?.toString() ?: EM_DASH

        // Location + jurisdiction adjective.
        val pickupLocation = reservation.locationFrom?.name ?: EM_DASH
        val pickupCountryEntity = reservation.locationFrom?.country
        val pickupCountry = pickupCountryEntity?.name ?: EM_DASH
        val countryAdjective = countryAdjective(pickupCountryEntity)

        // Dates.
        val checkInDate = reservation.dateFrom!!.format(DATE_FORMATTER)
        val checkInTime = reservation.dateFrom!!.format(TIME_FORMATTER)
        val checkOutDate = reservation.dateTo!!.format(DATE_FORMATTER)
        val checkOutTime = reservation.dateTo!!.format(TIME_FORMATTER)
        val nightsCount = ChronoUnit.DAYS.between(
            reservation.dateFrom!!.toLocalDate(),
            reservation.dateTo!!.toLocalDate(),
        )

        // Pricing.
        val currencyCode = reservation.currency ?: "EUR"
        val currencySymbol = runCatching {
            Currency.getInstance(currencyCode).getSymbol(Locale.ENGLISH).toString()
        }.getOrDefault(currencyCode)
        val basePrice = reservation.basePrice ?: BigDecimal.ZERO
        val discount = reservation.discount ?: BigDecimal.ZERO
        val clientPrice = reservation.clientPrice ?: BigDecimal.ZERO
        val totalPrice = reservation.totalPrice ?: BigDecimal.ZERO
        val hasDiscount = discount.compareTo(BigDecimal.ZERO) > 0

        val basePriceLabel = "${basePrice.toPlainString()}$currencySymbol"
        val discountLabel = "${discount.toPlainString()}$currencySymbol"
        val clientPriceLabel = "${clientPrice.toPlainString()}$currencySymbol"
        val totalPriceLabel = "${totalPrice.toPlainString()}$currencySymbol"

        // Payment phases — sorted by deadline. Labels: 1st = Deposit
        // payment, last (when >1) = Final balance payment, intermediate =
        // 2nd / 3rd / Nth payment.
        val sortedPhases = flow.paymentPhases.sortedBy { it.deadline }
        val paymentPhases: List<Map<String, Any?>> = sortedPhases.mapIndexed { idx, p ->
            val label = when {
                idx == 0 -> "Deposit payment"
                idx == sortedPhases.size - 1 && sortedPhases.size > 1 -> "Final balance payment"
                idx == 1 -> "2nd payment"
                idx == 2 -> "3rd payment"
                else -> "${idx + 1}th payment"
            }
            mapOf(
                "label" to label,
                "amountLabel" to "${p.amount.toPlainString()}$currencySymbol",
                "deadlineLabel" to p.deadline.format(DATE_FORMATTER),
                "isPaid" to (p.paidOn != null),
            )
        }
        val hasUnpaidPhase = sortedPhases.any { it.paidOn == null }

        // Charter type (Bareboat vs Skippered) — derived from extras.
        val charterType = run {
            val hasSkipper = flow.reservationExtras.any { e ->
                e.name?.contains("skipper", ignoreCase = true) == true
            }
            if (hasSkipper) "Skippered charter" else "Bareboat charter"
        }

        // Obligatory extras — surfaced in the contract so the Charterer sees
        // exactly what they're committed to beyond the base charter fee
        // (transit log, end-cleaning, security deposit when bookable in
        // advance, harbour fees, tourist tax, etc.). Filter:
        //   - obligatory == true
        //   - exclude skipper line (it's already reflected in `charterType`)
        // `payableAtBase = true` means settled on-site at the marina (cash /
        // card at check-in). False/null means it's bundled in the base wire
        // payment. We expose both so the Charterer reconciles each row.
        val obligatoryExtras: List<Map<String, Any?>> = flow.reservationExtras
            .filter { it.obligatory == true }
            .filterNot { it.name?.contains("skipper", ignoreCase = true) == true }
            .map { e ->
                val priceLabel = "${(e.price ?: BigDecimal.ZERO).toPlainString()}$currencySymbol"
                val payAtMarina = e.payableAtBase == true
                mapOf(
                    "name" to (e.name ?: EM_DASH),
                    "priceLabel" to priceLabel,
                    "unitLabel" to (e.unit?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: ""),
                    "payAtMarina" to payAtMarina,
                    "settlementLabel" to if (payAtMarina) "At marina" else "In advance",
                )
            }

        return mapOf(
            // Booking
            "reservationNumber" to displayReservationRef,
            "agreementDate" to agreementDate,

            // Charterer
            "clientFullName" to clientFullName,
            "clientFirstName" to clientFirstName,
            "clientLastName" to clientLastName,
            "clientEmail" to clientEmail,
            "clientPhone" to clientPhone,
            "clientAddress" to clientAddress,
            "clientCity" to clientCity,
            "clientCountry" to clientCountry,

            // Broker
            "brokerName" to "Cusmanich d.o.o.",
            "brokerTradingAs" to "Boat4You",
            "brokerAddress" to "Vrboran 37, 21000 Split, Croatia",
            "brokerOib" to "HR87394862517",
            "brokerEmail" to "info@boat4you.com",
            "brokerPhone" to "+385 91 3000 009",
            // CEO signature image — base64 data URL embedded in the PDF.
            // Rendered above the broker signature line so the document
            // looks signed even though it ships as a one-shot attachment.
            "signatureImageDataUrl" to signatureDataUrl,

            // Yacht
            "yachtFullLabel" to yachtFullLabel,
            "yachtYearBuilt" to yachtYearBuilt,
            "yachtLengthMeters" to yachtLengthMeters,
            "yachtCabins" to yachtCabins,
            "charterType" to charterType,

            // Location
            "pickupLocation" to pickupLocation,
            "pickupCountry" to pickupCountry,
            "countryAdjective" to countryAdjective,

            // Dates
            "checkInDate" to checkInDate,
            "checkInTime" to checkInTime,
            "checkOutDate" to checkOutDate,
            "checkOutTime" to checkOutTime,
            "nightsCount" to nightsCount,

            // Pricing
            "currency" to currencyCode,
            "currencySymbol" to currencySymbol,
            "basePriceLabel" to basePriceLabel,
            "discountLabel" to discountLabel,
            "clientPriceLabel" to clientPriceLabel,
            "totalPriceLabel" to totalPriceLabel,
            "hasDiscount" to hasDiscount,

            // Payment
            "paymentPhases" to paymentPhases,
            "hasUnpaidPhase" to hasUnpaidPhase,

            // Obligatory extras (mandatory line items beyond base charter)
            "obligatoryExtras" to obligatoryExtras,
            "hasObligatoryExtras" to obligatoryExtras.isNotEmpty(),
        )
    }

    /**
     * Map of country code (ISO-3166-1 alpha-2) to the adjectival form used
     * in the T&C jurisdiction clauses (e.g. "Croatian law", "Greek law").
     * Defaults to "Croatian" since Cusmanich d.o.o. is a Croatian broker —
     * most charters are in Croatia, and the Croatian fallback won't change
     * the legal venue (the agreement still names Split as the place of
     * arbitration).
     */
    private fun countryAdjective(country: Country?): String {
        return when (country?.code2?.uppercase()) {
            "HR" -> "Croatian"
            "GR" -> "Greek"
            "IT" -> "Italian"
            "ES" -> "Spanish"
            "PT" -> "Portuguese"
            "FR" -> "French"
            "TR" -> "Turkish"
            "ME" -> "Montenegrin"
            else -> "Croatian"
        }
    }

    companion object {
        private const val TEMPLATE_NAME = "contract/charterAgreement"
        private const val BASE_URI = "classpath:/templates/contract/"
        private const val EM_DASH = "—"
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH)
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
