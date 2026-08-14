package hr.workspace.boat4you.domains.catalouge.services

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.service.BankTransferFeeShare
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
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
    private val settingsService: AdminSettingsService,
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
        // F4-012: PDF render is an admin-side operation on a confirmed
        // reservation — `reservationFlow`, its `yacht`, and its `user`
        // are all required for the template variables. The earlier
        // `!!` chain would NPE with no context if any link was null
        // (the F2-041 fictitious-reservation edge case left some of
        // these unset). Explicit guards surface which field is
        // missing as a real IllegalStateException, caught by the
        // outer error handler as 500 GENERAL_ERROR with structured
        // logging for ops.
        val flow = reservation.reservationFlow
            ?: throw IllegalStateException("Cannot render charter agreement: reservation has no reservation_flow (id=${reservation.id})")
        val yacht = flow.yacht
            ?: throw IllegalStateException("Cannot render charter agreement: reservation_flow has no yacht (flowId=${flow.id})")
        val user = flow.user
            ?: throw IllegalStateException("Cannot render charter agreement: reservation_flow has no user (flowId=${flow.id})")

        val displayReservationRef = reservation.reservationNumber ?: reservation.id?.toString() ?: EM_DASH
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

        // Yacht — Mario rule: Manufacturer + Model + Name. Partner model names
        // often already START with the manufacturer ("Fountaine Pajot Lucia
        // 40", "Lagoon 42") — joining blindly printed the brand twice
        // (found on 100184/2026, 14.8.2026), so the manufacturer prefix is
        // skipped when the model carries it.
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModelName = yacht.model?.name?.takeIf { it.isNotBlank() }
        val yachtName = yacht.name?.takeIf { it.isNotBlank() }
        // Doubling shows up two ways: the model repeats the full manufacturer
        // ("Fountaine Pajot Lucia 40") or just its brand word ("Bali 4.2" vs
        // "Bali Catamarans") — compare first words so both collapse.
        val manufacturerPrefix = yachtManufacturer?.takeIf { m ->
            val modelFirstWord = yachtModelName?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            val manufacturerFirstWord = m.trim().split(Regex("\\s+")).firstOrNull()
            modelFirstWord == null || !modelFirstWord.equals(manufacturerFirstWord, ignoreCase = true)
        }
        val yachtFullLabel = listOfNotNull(manufacturerPrefix, yachtModelName, yachtName)
            .joinToString(" ")
            .ifBlank { yachtName ?: EM_DASH }

        val yachtYearBuilt = yacht.buildYear?.toString() ?: EM_DASH
        val yachtLengthMeters = yacht.length?.toPlainString() ?: EM_DASH
        val yachtCabins = yacht.cabins?.toString() ?: EM_DASH

        // Operator (charter agency) — the yacht's agency, named as a PARTY on
        // the agreement (template rework 14.8.2026): the charter contract is
        // concluded directly between the Charterer and the Operator, Boat4You
        // acts as booking intermediary only. Data comes from the partner-API
        // agency sync (~99% of active agencies carry address+email, ~96%
        // phone); anything missing renders as an em dash rather than blocking
        // the PDF.
        val agency = yacht.agency
        val operatorName = agency?.name?.takeIf { it.isNotBlank() } ?: EM_DASH
        // Partner address fields are inconsistent: some agencies keep street
        // only ("104, Saki Karagiorga Str."), others embed zip+city with a
        // newline ("Kraljice Jelene 3\n23210 Biograd na Moru") while ALSO
        // filling the separate zip/city columns — naive composition printed
        // the city twice (found on Angelina Yachtcharter / Croatia Yachting,
        // 14.8.2026). Flatten newlines and only append a part the address
        // doesn't already contain.
        val operatorStreet = agency?.address
            ?.replace(Regex("\\s*\\r?\\n+\\s*"), ", ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val operatorCity = agency?.city?.takeIf { it.isNotBlank() }
        val operatorZip = agency?.zip?.takeIf { it.isNotBlank() }
        val operatorCountry = agency?.country?.takeIf { it.isNotBlank() }
        val zipCityPart = listOfNotNull(operatorZip, operatorCity)
            .joinToString(" ")
            .takeIf { it.isNotBlank() && (operatorCity == null || operatorStreet?.contains(operatorCity, ignoreCase = true) != true) }
        val countryPart = operatorCountry
            ?.takeIf { operatorStreet?.contains(it, ignoreCase = true) != true }
        val operatorAddress = listOfNotNull(operatorStreet, zipCityPart, countryPart)
            .joinToString(", ")
            .ifBlank { EM_DASH }
        // Only name + registered address + VAT id appear on the agreement
        // (Mario 14.8.2026) — the Operator's base & 24h contact details are
        // deliberately NOT printed here; they go on the Charter Voucher issued
        // after full payment (T&C 3.4), keeping the pre-payment document free
        // of direct-contact leakage.
        val operatorVat = agency?.vatCode?.takeIf { it.isNotBlank() } ?: EM_DASH

        // Security deposit — the offer's period-specific amount wins (it is
        // what the partner actually charges for THIS week); yacht-level
        // deposit is the fallback for flows whose offer link is gone.
        val securityDeposit = flow.offer?.deposit ?: yacht.deposit
        val hasSecurityDeposit = securityDeposit != null && securityDeposit.signum() > 0

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
        // --- Pricing, computed from GROUND TRUTH, not the stored
        // reservation.clientPrice/discount fields which held the raw partner
        // (Nausys) values: discount was the PERCENT (rendered as "20.0€"),
        // clientPrice omitted our broker discount, and totalPrice didn't match
        // the payment schedule (Mario 12.7.2026, booking 1441002).
        //
        // Extras split by settlement. `payableAtBase == true` = paid on-site at
        // the marina (charter pack, harbour fees). Everything else is folded
        // into what we collect online (crew, APA, with-booking items).
        val marinaExtras = flow.reservationExtras.filter { it.payableAtBase == true }
        val inPaymentExtras = flow.reservationExtras.filter { it.payableAtBase != true }
        val inPaymentExtrasSum = inPaymentExtras.sumOf { it.price ?: BigDecimal.ZERO }

        val basePrice = (reservation.basePrice ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        // What the Charterer pays us online — the pre-surcharge calculated total
        // (charter fee after ALL discounts + the extras bundled into the
        // booking). Falls back to the payment-phase sum, then the stored total.
        val totalPayable = (
            flow.calculatedTotalPrice
                ?: flow.paymentPhases.sumOf { it.amount }.takeIf { it.signum() > 0 }
                ?: reservation.totalPrice
                ?: BigDecimal.ZERO
        ).setScale(2, RoundingMode.HALF_UP)
        // Charter fee alone = online total minus the extras folded into it.
        val clientPrice = (totalPayable - inPaymentExtrasSum).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        // Discount = the euro gap between the operator's list price and our
        // charter fee (bundles the partner discount + any broker discount).
        val discountAmount = (basePrice - clientPrice).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        val hasDiscount = discountAmount.signum() > 0

        val basePriceLabel = "${basePrice.toPlainString()}$currencySymbol"
        val discountLabel = "${discountAmount.toPlainString()}$currencySymbol"
        val clientPriceLabel = "${clientPrice.toPlainString()}$currencySymbol"
        val totalPriceLabel = "${totalPayable.toPlainString()}$currencySymbol"

        // Extras bundled into the online payment (skipper, crew, …) — itemised
        // in the Charter price block so the Total reconciles to the payment
        // schedule. Empty for a plain bareboat booking with no such extras.
        val inPaymentExtrasList: List<Map<String, Any?>> = inPaymentExtras.map { e ->
            mapOf(
                "name" to (e.name ?: EM_DASH),
                "priceLabel" to "${(e.price ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString()}$currencySymbol",
            )
        }

        // Payment phases — sorted by deadline. Labels: 1st = Deposit
        // payment, last (when >1) = Final balance payment, intermediate =
        // 2nd / 3rd / Nth payment.
        val sortedPhases = flow.paymentPhases.sortedBy { it.deadline }
        val phaseCount = sortedPhases.size
        fun phaseLabel(idx: Int): String = when {
            idx == 0 -> "Deposit payment"
            idx == phaseCount - 1 && phaseCount > 1 -> "Final balance payment"
            idx == 1 -> "2nd payment"
            idx == 2 -> "3rd payment"
            else -> "${idx + 1}th payment"
        }

        // Two payment-method options — the SAME instalment schedule presented
        // twice, each with its payment-processing fee folded in, mirroring
        // exactly what the system collects (Mario 12.7.2026, booking 1441002:
        // the paid amount must reconcile with the client's chosen method and
        // our accounting). Bank transfer = BANK_TRANSFER_FIXED_FEE split
        // whole-euro across phases (BankTransferFeeShare — same math as the
        // wire-payment emails). Credit card = CARD_PAYMENT_SURCHARGE % per
        // phase, whole-euro HALF_UP (same math as StripePaymentService, so the
        // contract equals the Stripe charge).
        val cardSurchargePct = settingsService.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE).value
            ?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val bankTransferFee = settingsService.getSetting(SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE).value
            ?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val bankPhases = mutableListOf<Map<String, Any?>>()
        val cardPhases = mutableListOf<Map<String, Any?>>()
        var bankOptionTotal = BigDecimal.ZERO
        var cardOptionTotal = BigDecimal.ZERO
        sortedPhases.forEachIndexed { idx, p ->
            val base = p.amount.setScale(2, RoundingMode.HALF_UP)
            val bankShare = BankTransferFeeShare.shareFor(bankTransferFee, phaseCount, idx)
            val bankAmount = (base + bankShare).setScale(2, RoundingMode.HALF_UP)
            val cardSurcharge = base.multiply(cardSurchargePct).divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
            val cardAmount = (base + cardSurcharge).setScale(2, RoundingMode.HALF_UP)
            bankOptionTotal += bankAmount
            cardOptionTotal += cardAmount
            val label = phaseLabel(idx)
            val deadlineLabel = p.deadline.format(DATE_FORMATTER)
            bankPhases += mapOf(
                "label" to label,
                "amountLabel" to "${bankAmount.toPlainString()}$currencySymbol",
                "deadlineLabel" to deadlineLabel,
            )
            cardPhases += mapOf(
                "label" to label,
                "amountLabel" to "${cardAmount.toPlainString()}$currencySymbol",
                "deadlineLabel" to deadlineLabel,
            )
        }
        val hasPaymentPhases = sortedPhases.isNotEmpty()
        val hasCardFee = cardSurchargePct.signum() > 0
        val hasBankFee = bankTransferFee.signum() > 0
        val cardSurchargePercentLabel = "${cardSurchargePct.stripTrailingZeros().toPlainString()}%"
        val bankFeeLabel = "${bankTransferFee.setScale(2, RoundingMode.HALF_UP).toPlainString()}$currencySymbol"
        val bankTotalLabel = "${bankOptionTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()}$currencySymbol"
        val cardTotalLabel = "${cardOptionTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()}$currencySymbol"
        val paymentLink = "https://www.boat4you.com/my-bookings/${reservation.id}"

        // Charter type (Bareboat vs Skippered) — derived from extras.
        val charterType = run {
            val hasSkipper = flow.reservationExtras.any { e ->
                e.name?.contains("skipper", ignoreCase = true) == true
            }
            if (hasSkipper) "Skippered charter" else "Bareboat charter"
        }

        // Extras paid on-site at the marina (cash / card at check-in) — charter
        // pack, harbour fees, tourist tax, etc. Listed in their own block with
        // an "At marina" label so the Charterer knows these are NOT part of the
        // online payment schedule above. (In-payment extras like the skipper
        // are itemised in the Charter price block instead.)
        val obligatoryExtras: List<Map<String, Any?>> = marinaExtras.map { e ->
            mapOf(
                "name" to (e.name ?: EM_DASH),
                "priceLabel" to "${(e.price ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString()}$currencySymbol",
                "unitLabel" to (e.unit?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: ""),
                "payAtMarina" to true,
                "settlementLabel" to "At the base",
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

            // Operator (charter agency) — party to the charter contract.
            "operatorName" to operatorName,
            "operatorAddress" to operatorAddress,
            "operatorVat" to operatorVat,

            // Security deposit (payable directly to the Operator at check-in).
            "hasSecurityDeposit" to hasSecurityDeposit,
            "securityDepositLabel" to (
                securityDeposit?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.plus(currencySymbol) ?: EM_DASH
            ),

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
            // Extras bundled into the online payment (skipper, crew), itemised
            // in the Charter price block between Client price and Total.
            "inPaymentExtras" to inPaymentExtrasList,
            "hasInPaymentExtras" to inPaymentExtrasList.isNotEmpty(),

            // Payment — two method options (bank transfer / credit card),
            // each with its processing fee folded into the instalment amounts.
            "hasPaymentPhases" to hasPaymentPhases,
            "bankPhases" to bankPhases,
            "cardPhases" to cardPhases,
            "bankTotalLabel" to bankTotalLabel,
            "cardTotalLabel" to cardTotalLabel,
            "hasBankFee" to hasBankFee,
            "hasCardFee" to hasCardFee,
            "bankFeeLabel" to bankFeeLabel,
            "cardSurchargePercentLabel" to cardSurchargePercentLabel,
            "paymentLink" to paymentLink,

            // Extras payable at the marina (charter pack, harbour fees, …)
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
