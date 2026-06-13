package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.domains.catalouge.services.CharterAgreementService
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationYachtSwapAudit
import hr.workspace.boat4you.domains.reservation.jpa.YachtSwapAction
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

// Every customer-facing money string is rounded to 2 decimals (HALF_UP — same
// convention as the Stripe charge in StripePaymentService.toCentsLong). Raw
// BigDecimal totals can carry partner-side sub-cent artefacts (e.g. a total of
// 974.99994 €) that must never reach a customer's eyes; 974.99994 -> "975.00".
private fun BigDecimal.money(): String = this.setScale(2, RoundingMode.HALF_UP).toPlainString()

@Service
@Transactional(readOnly = true)
class ReservationEmailService(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    private val reservationRepository: ReservationRepository,
    private val messageSource: MessageSource,
    private val charterAgreementService: CharterAgreementService,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
    @Value("\${server.host-admin-public}") private val serverHostAdminPublic: String,
) {
    fun sendOptionCreatedEmail(reservationId: Long) {
        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val expiryFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy 'at' HH:mm")
        // Header "Received {date·time (GMT±X)}" — same Europe/Zagreb zone as
        // inquiry email so admin/customer see the same wall-clock time the
        // booking actually landed on the server. Format: `Apr 24, 2026 · 11:12 (GMT+2)`.
        val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        val receivedAtFormatted = reservation.createdAt?.let { ts ->
            val zoned = ts.atZone(java.time.ZoneId.of("Europe/Zagreb"))
            val offsetHours = zoned.offset.totalSeconds / 3600
            val sign = if (offsetHours >= 0) "+" else "-"
            "${zoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"
        } ?: ""

        // Display booking number in emails (e.g. "100176/2026"); fall back to
        // the internal id for historical rows that predate the feature.
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        // Real partner option deadline (MMK expirationDate / NauSys optionTill).
        // Pass an empty string when the partner returned null so Thymeleaf
        // `th:if="${optionExpiresAt != ''}"` can hide the row gracefully —
        // we deliberately do NOT substitute a fake "48h from now" here, as
        // misrepresenting the deadline is the whole class of bug this email
        // change is fixing (short partner options being silently extended in
        // the customer's mind).
        val optionExpiresAtFormatted = reservation.optionExpiresAt?.format(expiryFormatter) ?: ""

        val user = reservation.reservationFlow!!.user!!
        val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${reservation.reservationFlow!!.email!!}>"
            else reservation.reservationFlow!!.email!!

        // Yacht reference per Mario rule: Manufacturer + Model + Name. Fall back
        // gracefully when manufacturer or model is missing on legacy rows.
        val yacht = reservation.reservationFlow!!.yacht!!
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModel = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModel, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val currencySymbol = Currency.getInstance(reservation.currency).getSymbol(Locale.getDefault()).toString()
        val totalPriceLabel = "${(reservation.totalPrice ?: java.math.BigDecimal.ZERO).money()}$currencySymbol"

        // Locale must be resolved BEFORE we localise per-row payment / extras
        // labels — Customer view uses user.language, admin uses English.
        val customerLocale = resolveEmailLocale(user.language)
        // Reusable helper for any caller that needs a localised
        // `Per <unit>` suffix on extras (per booking, per night, per pet,
        // ...). Keys live under `extrasUnit.*` in email_*.properties; the
        // raw enum name is uppercased + camelCased to form the key tail.
        fun unitLabel(unit: hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType?, loc: Locale): String {
            if (unit == null) return ""
            val key = "extrasUnit." + when (unit) {
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_WEEK -> "perWeek"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_WEEK_PERSON -> "perWeekPerson"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOOKING -> "perBooking"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOOKING_PERSON -> "perBookingPerson"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_NIGHT -> "perNight"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_NIGHT_PERSON -> "perNightPerson"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOAT -> "perBoat"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_HOUR -> "perHour"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_PIECE -> "perPiece"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_LITRE -> "perLitre"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_MEAL -> "perMeal"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_NM -> "perNm"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_PACK -> "perPack"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_PET -> "perPet"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_SET -> "perSet"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BED -> "perBed"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_TANK -> "perTank"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_TON -> "perTon"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_TRIP -> "perTrip"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_GB -> "perGb"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOTTLE -> "perBottle"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_CABIN -> "perCabin"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_LICENCE -> "perLicence"
                else -> return ""
            }
            return runCatching { messageSource.getMessage(key, null, loc) }.getOrDefault("")
        }

        // Payment schedule — partner-aware phases ordered by deadline. Each
        // entry carries a localised "1st payment" / "2nd payment" /
        // "Final payment" label so the email reads naturally even for
        // multi-phase plans (NauSys 30/40/30 split, MMK 30/70, etc.).
        val sortedPhases = reservation.reservationFlow!!.paymentPhases.sortedBy { it.deadline }
        val phaseViews: List<Map<String, Any?>> = sortedPhases.mapIndexed { idx, p ->
            val ordKey = when {
                idx == sortedPhases.size - 1 && sortedPhases.size > 1 -> "fewMoreDetails.phaseFinal"
                idx == 0 -> "fewMoreDetails.phaseFirst"
                idx == 1 -> "fewMoreDetails.phaseSecond"
                else -> "fewMoreDetails.phaseNth"
            }
            val ordLabel = runCatching {
                messageSource.getMessage(ordKey, arrayOf<Any>(idx + 1), customerLocale)
            }.getOrDefault("Payment ${idx + 1}")
            mapOf(
                "label" to ordLabel,
                "amountLabel" to "${p.amount.money()}$currencySymbol",
                "deadlineLabel" to p.deadline.format(dateFormatter),
                "isPaid" to (p.paidOn != null),
            )
        }

        // Due-now = first unpaid phase. When the option email goes out the
        // customer hasn't paid anything yet, so this is normally phase #1.
        val firstUnpaid = sortedPhases.firstOrNull { it.paidOn == null }
        val dueNowLabel = firstUnpaid?.let { "${it.amount.money()}$currencySymbol" } ?: totalPriceLabel
        val dueNowDeadlineLabel = firstUnpaid?.deadline?.format(dateFormatter) ?: ""

        // Extras — split by payment timing. `payableAtBase = true` means
        // the customer settles it on-site (cash/card at the marina),
        // false means it's bundled into the base wire transfer.
        val resvExtras = reservation.reservationFlow!!.reservationExtras
        fun extraToView(e: hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra): Map<String, Any?> {
            val priceVal = e.price ?: java.math.BigDecimal.ZERO
            return mapOf(
                "name" to (e.name ?: "—"),
                "priceLabel" to "${priceVal.money()}$currencySymbol",
                "unitLabel" to unitLabel(e.unit, customerLocale),
                "obligatory" to (e.obligatory == true),
            )
        }
        val extrasInBase = resvExtras.filter { it.payableAtBase != true }.map(::extraToView)
        val extrasOnSite = resvExtras.filter { it.payableAtBase == true }.map(::extraToView)

        // "Available at the marina" — full yacht catalog minus what the
        // customer already added. Helps the customer scan add-ons (early
        // check-in, water toys, skipper, …) without going back to the
        // boat page.
        val addedExtraIds = resvExtras.mapNotNull { it.extras?.id }.toSet()
        val availableAtMarina: List<Map<String, Any?>> = yacht.yachtExtras
            .filter { it.extras?.id !in addedExtraIds }
            .map { ye ->
                mapOf(
                    "name" to (ye.name ?: "—"),
                    "priceLabel" to "${(ye.price ?: java.math.BigDecimal.ZERO).money()}$currencySymbol",
                    "unitLabel" to unitLabel(ye.unit, customerLocale),
                    "obligatory" to (ye.obligatory == true),
                )
            }

        val variables =
            mapOf(
                "fullName" to fullName,
                "reservationId" to displayReservationRef,
                "yachtImageUrl" to yachtImageUrl,
                "yachtFullLabel" to yachtFullLabel,
                "yachtName" to yachtName,
                "yachtModel" to yachtModel,
                "locationFrom" to "${reservation.locationFrom!!.name}",
                "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
                "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br>${reservation.dateFrom!!.format(hourFormatter)}",
                "dropOffDateHourPeriod" to
                    "${reservation.dateTo!!.format(dateFormatter)}<br>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
                "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
                "pickupLocation" to reservation.locationFrom!!.name,
                "totalPrice" to totalPriceLabel,
                "dueNowLabel" to dueNowLabel,
                "dueNowDeadlineLabel" to dueNowDeadlineLabel,
                "paymentPhases" to phaseViews,
                "extrasInBase" to extrasInBase,
                "extrasOnSite" to extrasOnSite,
                "availableAtMarina" to availableAtMarina,
                "tncUrl" to "$serverHostPublic/terms-and-conditions",
                "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
                "publicUrl" to serverHostPublic,
                "optionExpiresAt" to optionExpiresAtFormatted,
                "receivedAt" to receivedAtFormatted,
                "currentYear" to LocalDate.now().year.toString(),
            )
        val customerSubject = messageSource.getMessage("fewMoreDetails.subject", null, customerLocale)
        emailService.sendEmail(
            recipients = listOf(recipientAddress),
            subject = customerSubject,
            templateName = "email/fewMoreDetails",
            variables = variables,
            locale = customerLocale,
        )

        // Admin email — always English (Mario rule for internal-team comms).
        emailService.sendEmail(
            recipients = adminEmails,
            subject = "Reservation has been opened for user $fullName! [#$displayReservationRef]",
            templateName = "email/fewMoreDetails",
            variables = variables,
            locale = Locale.ENGLISH,
        )
    }

    @Transactional(readOnly = true)
    fun sendConfirmationForReserved(
        reservation: ReservationDto,
        paymentType: PaymentType,
    ) {
        val reservation = reservationRepository.findById(reservation.id).orElseThrow()
        val yachtImageUrl = reservation.reservationFlow!!.yacht!!.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
        // Header "Confirmed {date · time (GMT±X)}" — same Europe/Zagreb wall-
        // clock format as inquiry / fewMoreDetails so the customer sees a
        // single timestamp style across the booking flow.
        val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        // The "confirmed at" timestamp comes from when this email is being
        // sent (= when the partner/PSP webhook flipped the booking to
        // confirmed). We don't have a dedicated `confirmedAt` column, so
        // approximate with `now()` in the right zone — the row's own
        // `updatedAt` would also work but is less robust across retries.
        val nowZoned = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Zagreb"))
        val offsetHours = nowZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        val receivedAtFormatted = "${nowZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"

        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val user = reservation.reservationFlow!!.user!!
        val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${reservation.reservationFlow!!.email!!}>"
            else reservation.reservationFlow!!.email!!

        // Yacht reference per Mario rule: Manufacturer + Model + Name.
        val yacht = reservation.reservationFlow!!.yacht!!
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModelName = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModelName, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val currencySymbol =
            Currency
                .getInstance(reservation.currency)
                .getSymbol(Locale.getDefault())
                .toString()
        val totalPriceLabel = "${(reservation.totalPrice ?: BigDecimal.ZERO).money()}$currencySymbol"

        // Customer locale = stored user.language (set when the booking was
        // initiated). Falls back to English if missing.
        val customerLocale = resolveEmailLocale(user.language)

        // Reusable extras-unit localiser — same logic as sendOptionCreatedEmail.
        fun unitLabel(unit: hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType?, loc: Locale): String {
            if (unit == null) return ""
            val key = "extrasUnit." + when (unit) {
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_WEEK -> "perWeek"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_WEEK_PERSON -> "perWeekPerson"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOOKING -> "perBooking"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOOKING_PERSON -> "perBookingPerson"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_NIGHT -> "perNight"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_NIGHT_PERSON -> "perNightPerson"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOAT -> "perBoat"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_HOUR -> "perHour"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_PIECE -> "perPiece"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_LITRE -> "perLitre"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_MEAL -> "perMeal"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_NM -> "perNm"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_PACK -> "perPack"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_PET -> "perPet"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_SET -> "perSet"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BED -> "perBed"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_TANK -> "perTank"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_TON -> "perTon"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_TRIP -> "perTrip"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_GB -> "perGb"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_BOTTLE -> "perBottle"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_CABIN -> "perCabin"
                hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType.PER_LICENCE -> "perLicence"
                else -> return ""
            }
            return runCatching { messageSource.getMessage(key, null, loc) }.getOrDefault("")
        }

        // Payment phases — partner-aware. By the time this email goes out at
        // least one phase is settled (the bank wire that triggered confirm,
        // or the card payment that paid in full). Mark each phase paid/unpaid
        // from `paidOn`. Single-phase plans don't render a schedule (the
        // template hides the section when size <= 1).
        val sortedPhases = reservation.reservationFlow!!.paymentPhases.sortedBy { it.deadline }
        val phaseViews: List<Map<String, Any?>> = sortedPhases.mapIndexed { idx, p ->
            val ordKey = when {
                idx == sortedPhases.size - 1 && sortedPhases.size > 1 -> "reservationConfirmed.phaseFinal"
                idx == 0 -> "reservationConfirmed.phaseFirst"
                idx == 1 -> "reservationConfirmed.phaseSecond"
                else -> "reservationConfirmed.phaseNth"
            }
            val ordLabel = runCatching {
                messageSource.getMessage(ordKey, arrayOf<Any>(idx + 1), customerLocale)
            }.getOrDefault("Payment ${idx + 1}")
            mapOf(
                "label" to ordLabel,
                "amountLabel" to "${p.amount.money()}$currencySymbol",
                "deadlineLabel" to p.deadline.format(dateFormatter),
                "isPaid" to (p.paidOn != null),
            )
        }

        // "Just paid" amount = the most-recent paid phase (CARD pays them all
        // in one go = `totalPrice`; BANK_TRANSFER pays them one at a time).
        val justPaidPhase = sortedPhases.lastOrNull { it.paidOn != null }
        val paidAmountLabel = justPaidPhase?.let { "${it.amount.money()}$currencySymbol" } ?: totalPriceLabel
        // Method label is rendered on the green pill — localised so e.g. DE
        // users see "Banküberweisung" / "Kreditkarte".
        val methodKey = if (paymentType == PaymentType.BANK_TRANSFER)
            "reservationConfirmed.methodBank"
        else
            "reservationConfirmed.methodCard"
        val paymentMethodLabel = runCatching {
            messageSource.getMessage(methodKey, null, customerLocale)
        }.getOrDefault(if (paymentType == PaymentType.BANK_TRANSFER) "bank transfer" else "credit card")

        // Extras split — same logic as sendOptionCreatedEmail.
        val resvExtras = reservation.reservationFlow!!.reservationExtras
        fun extraToView(e: hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra): Map<String, Any?> {
            val priceVal = e.price ?: BigDecimal.ZERO
            return mapOf(
                "name" to (e.name ?: "—"),
                "priceLabel" to "${priceVal.money()}$currencySymbol",
                "unitLabel" to unitLabel(e.unit, customerLocale),
                "obligatory" to (e.obligatory == true),
            )
        }
        val extrasInBase = resvExtras.filter { it.payableAtBase != true }.map(::extraToView)
        val extrasOnSite = resvExtras.filter { it.payableAtBase == true }.map(::extraToView)
        val addedExtraIds = resvExtras.mapNotNull { it.extras?.id }.toSet()
        val availableAtMarina: List<Map<String, Any?>> = yacht.yachtExtras
            .filter { it.extras?.id !in addedExtraIds }
            .map { ye ->
                mapOf(
                    "name" to (ye.name ?: "—"),
                    "priceLabel" to "${(ye.price ?: BigDecimal.ZERO).money()}$currencySymbol",
                    "unitLabel" to unitLabel(ye.unit, customerLocale),
                    "obligatory" to (ye.obligatory == true),
                )
            }

        val variables =
            mapOf(
                "fullName" to fullName,
                "reservationId" to displayReservationRef,
                "yachtImageUrl" to yachtImageUrl,
                "yachtFullLabel" to yachtFullLabel,
                "yachtName" to yachtName,
                "yachtModel" to yachtModelName,
                "locationFrom" to "${reservation.locationFrom!!.name}",
                "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
                "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br>${reservation.dateFrom!!.format(hourFormatter)}",
                "dropOffDateHourPeriod" to
                    "${reservation.dateTo!!.format(dateFormatter)}<br>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
                "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
                "pickupLocation" to reservation.locationFrom!!.name,
                "totalPrice" to totalPriceLabel,
                "paidAmountLabel" to paidAmountLabel,
                "paymentMethodLabel" to paymentMethodLabel,
                "paymentPhases" to phaseViews,
                "extrasInBase" to extrasInBase,
                "extrasOnSite" to extrasOnSite,
                "availableAtMarina" to availableAtMarina,
                "tncUrl" to "$serverHostPublic/terms-and-conditions",
                "reservationUrl" to "$serverHostPublic/my-bookings/${reservation.id}",
                "publicUrl" to serverHostPublic,
                "receivedAt" to receivedAtFormatted,
                "currentYear" to LocalDate.now().year.toString(),
            )

        val customerSubject = messageSource.getMessage(
            "reservationConfirmed.subject",
            arrayOf<Any>(displayReservationRef),
            customerLocale,
        )

        // Render the per-reservation charter agreement PDF and attach it to
        // the confirmation email. The agreement supersedes the legacy static
        // `terms-and-conditions-boat4you.pdf` (Page 2+ of the agreement
        // already embeds the full T&C inline). Filename uses the display
        // booking number (e.g. `100176/2026` -> `100176-2026`); slashes are
        // illegal in MIME attachment filenames on most clients.
        val pdfBytes = charterAgreementService.renderToPdf(reservation)
        val pdfFilename = "charter-agreement-${displayReservationRef.replace('/', '-')}.pdf"

        // Single confirmation template for both BANK_TRANSFER and CARD —
        // the only payment-method-specific copy on the page is the green
        // "Payment received via X" pill, and that text comes from the
        // `paymentMethodLabel` variable resolved above. The legacy split
        // (`reservationConfirmedPaymentCard.html` for CARD) was kept until
        // the templates were redesigned in early May 2026; once both files
        // converged to the same HTML, the split became dead code that just
        // confused everyone reading the templates folder.
        emailService.sendEmail(
            recipients = listOf(recipientAddress),
            subject = customerSubject,
            templateName = "email/reservationConfirmed",
            variables = variables,
            locale = customerLocale,
            dynamicAttachments = mapOf(pdfFilename to pdfBytes),
        )
    }

    @Transactional(readOnly = true)
    fun sendRequestCancellation(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val user = flow.user!!

        val yachtImageUrl = yacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
        // Header "Received {date · time (GMT±X)}" — same Europe/Zagreb format
        // as the rest of the customer flow so the wall-clock timestamp reads
        // identically across email types.
        val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        // The cancellation request stamps `cancelationRequestAt` on the flow
        // when the controller calls cancel-request; fall back to `now` for
        // legacy rows where the column was left null.
        val requestedAtSource = flow.cancelationRequestAt
        val requestedAtZoned = (
            requestedAtSource?.atZone(java.time.ZoneId.of("Europe/Zagreb"))
                ?: java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Zagreb"))
        )
        val offsetHours = requestedAtZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        val requestedAtFormatted = "${requestedAtZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"

        // Yacht reference per Mario rule: Manufacturer + Model + Name.
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModel = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModel, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val pickupDateHour = "${reservation.dateFrom!!.format(dateFormatter)}<br/>${reservation.dateFrom!!.format(hourFormatter)}"
        val dropOffDateHourPeriod =
            "${reservation.dateTo!!.format(dateFormatter)}<br/>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}"

        // Defensive HTML escape on the customer-supplied reason. Rendered via
        // `th:utext` in the template so multi-line input survives, but the
        // raw `<>&` are neutralised first to prevent any HTML injection.
        // Same pattern as InquiryEmailService.escapeAndLinebreak.
        val rawReason = flow.cancelationRequest.orEmpty()
        val cancellationReason = rawReason
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", "<br/>")

        val pickupLocation = reservation.locationFrom!!.name ?: ""
        val pickupCountry = reservation.locationFrom!!.country?.name ?: ""

        val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${flow.email!!}>"
            else flow.email!!

        val customerLocale = resolveEmailLocale(user.language)

        // Customer email — localised acknowledgement.
        val customerVariables = mapOf(
            "fullName" to fullName,
            "reservationId" to displayReservationRef,
            "yachtFullLabel" to yachtFullLabel,
            "yachtImageUrl" to yachtImageUrl,
            "pickupLocation" to pickupLocation,
            "pickupCountry" to pickupCountry,
            "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
            "pickupDateHour" to pickupDateHour,
            "dropOffDateHourPeriod" to dropOffDateHourPeriod,
            "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
            "cancellationReason" to cancellationReason,
            "requestedAt" to requestedAtFormatted,
            "currentYear" to LocalDate.now().year.toString(),
        )
        val customerSubject = messageSource.getMessage(
            "cancellationRequest.subject",
            arrayOf<Any>(displayReservationRef),
            customerLocale,
        )

        // Wrap each dispatch in its own try/catch — a customer ack landing
        // while the admin notif fails is better than neither, and vice versa.
        try {
            emailService.sendEmail(
                recipients = listOf(recipientAddress),
                subject = customerSubject,
                templateName = "email/cancellationRequest",
                variables = customerVariables,
                locale = customerLocale,
            )
        } catch (e: Exception) {
            // Don't propagate — the admin notif still has to fire. The
            // EmailService itself logs SMTP failures; this catch only
            // protects against any pre-send variable-resolution issues.
            org.slf4j.LoggerFactory
                .getLogger(this::class.java.name)
                .error("Failed to send customer cancellation acknowledgement for reservation $displayReservationRef", e)
        }

        // Admin email — always English, factual triage payload.
        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val adminVariables = mapOf(
            "reservationId" to displayReservationRef,
            "clientFullName" to (user.getFullName().trim().ifBlank { flow.email ?: "—" }),
            "clientEmail" to (flow.email ?: ""),
            "clientPhone" to (flow.phoneNumber?.takeIf { it.isNotBlank() } ?: user.phoneNumber?.takeIf { it.isNotBlank() } ?: "—"),
            "clientCountry" to (user.country ?: ""),
            "yachtFullLabel" to yachtFullLabel,
            "yachtImageUrl" to yachtImageUrl,
            "pickupLocation" to pickupLocation,
            "pickupDateHour" to pickupDateHour,
            "dropOffDateHourPeriod" to dropOffDateHourPeriod,
            "cancellationReason" to cancellationReason,
            "reservationUrl" to serverHostAdminPublic + "/bookings/" + reservation.id,
            "requestedAt" to requestedAtFormatted,
            "currentYear" to LocalDate.now().year.toString(),
        )

        try {
            emailService.sendEmail(
                recipients = adminEmails,
                subject = "Cancellation request — $displayReservationRef",
                templateName = "email/cancellationRequestAdmin",
                variables = adminVariables,
                // Reply-To = the customer who asked to cancel. Hitting Reply
                // in the admin's inbox lands a message directly on the
                // requesting client — no copy/paste of their email needed.
                replyTo = flow.email,
            )
        } catch (e: Exception) {
            org.slf4j.LoggerFactory
                .getLogger(this::class.java.name)
                .error("Failed to send admin cancellation notification for reservation $displayReservationRef", e)
        }
    }

    /**
     * Customer notification: "we couldn't approve your cancellation". Fires
     * after [ReservationMutationService.rejectCancellationRequest] has
     * stamped `cancelationRejectedAt` + `cancelationRejectedReason` on the
     * flow. The booking itself is NOT cancelled — the email explains why
     * (charter agency's policy) and reassures the customer the booking is
     * still on. Localised to the customer's language with the same
     * Europe/Zagreb wall-clock timestamp formatting used by the other
     * cancellation emails.
     */
    @Transactional(readOnly = true)
    fun sendCancellationRejected(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val user = flow.user!!

        val yachtImageUrl = yacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
        // Header "Decided {date · time (GMT±X)}" — same Europe/Zagreb format
        // as the cancellation-request ack so the wall-clock timestamp reads
        // identically across the two emails the customer receives.
        val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        val rejectedAtSource = flow.cancelationRejectedAt
        val rejectedAtZoned = (
            rejectedAtSource?.atZone(java.time.ZoneId.of("Europe/Zagreb"))
                ?: java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Zagreb"))
        )
        val offsetHours = rejectedAtZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        val rejectedAtFormatted = "${rejectedAtZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"

        // Yacht reference per Mario rule: Manufacturer + Model + Name.
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModel = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModel, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val pickupDateHour = "${reservation.dateFrom!!.format(dateFormatter)}<br/>${reservation.dateFrom!!.format(hourFormatter)}"
        val dropOffDateHourPeriod =
            "${reservation.dateTo!!.format(dateFormatter)}<br/>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}"

        // Defensive HTML escape on both reasons. Customer reason might contain
        // anything (free-text submitted from /my-bookings), admin reason is
        // typed by Mario from /admin/bookings — same `<>&` neutralisation +
        // `\n` -> `<br/>` as the cancellation-request ack so multi-line input
        // survives `th:utext` rendering without enabling HTML injection.
        fun escapeAndLinebreak(input: String?): String =
            input.orEmpty()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", "<br/>")

        val customerReason = escapeAndLinebreak(flow.cancelationRequest)
        val rejectionReason = escapeAndLinebreak(flow.cancelationRejectedReason)

        val pickupLocation = reservation.locationFrom!!.name ?: ""
        val pickupCountry = reservation.locationFrom!!.country?.name ?: ""

        val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${flow.email!!}>"
            else flow.email!!

        val customerLocale = resolveEmailLocale(user.language)

        val customerVariables = mapOf(
            "fullName" to fullName,
            "reservationId" to displayReservationRef,
            "yachtFullLabel" to yachtFullLabel,
            "yachtImageUrl" to yachtImageUrl,
            "pickupLocation" to pickupLocation,
            "pickupCountry" to pickupCountry,
            "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
            "pickupDateHour" to pickupDateHour,
            "dropOffDateHourPeriod" to dropOffDateHourPeriod,
            "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
            "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
            "customerReason" to customerReason,
            "rejectionReason" to rejectionReason,
            "rejectedAt" to rejectedAtFormatted,
            "currentYear" to LocalDate.now().year.toString(),
        )
        val customerSubject = messageSource.getMessage(
            "cancellationRejected.subject",
            arrayOf<Any>(displayReservationRef),
            customerLocale,
        )

        // Wrap dispatch in try/catch so a mail-side failure (SMTP outage,
        // bad template var resolution) doesn't roll back the JPA transaction
        // that already stamped the rejection on the flow. The rejection is
        // the source of truth in our DB; the email is best-effort delivery.
        try {
            emailService.sendEmail(
                recipients = listOf(recipientAddress),
                subject = customerSubject,
                templateName = "email/cancellationRejected",
                variables = customerVariables,
                locale = customerLocale,
            )
        } catch (e: Exception) {
            org.slf4j.LoggerFactory
                .getLogger(this::class.java.name)
                .error("Failed to send customer cancellation-rejected email for reservation $displayReservationRef", e)
        }
    }

    /**
     * Customer notification: "your cancellation is confirmed". Fires after
     * [ReservationMutationService.cancelReservation] has stamped sysStatus =
     * CANCELLED. Mirror of [sendCancellationRejected] but green-themed and
     * **without** any monetary detail — Mario rule (May 2026): refund and
     * paperwork are settled directly with the agency, never communicated by
     * automated email. The email lists the yacht + dates so the customer
     * has the booking ref handy when our team follows up.
     */
    @Transactional(readOnly = true)
    fun sendCancellationApproved(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val user = flow.user!!

        val yachtImageUrl = yacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
        // "Decided {date · time (GMT±X)}" — Europe/Zagreb wall-clock so the
        // header timestamp reads identically across rejected and approved.
        // No persisted "approvedAt" column on the flow, so fall back to "now"
        // if cancelationRequestAt is missing (admin direct-cancel path).
        val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        val decidedAtSource = flow.cancelationRequestAt
        val decidedAtZoned = (
            decidedAtSource?.atZone(java.time.ZoneId.of("Europe/Zagreb"))
                ?: java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Zagreb"))
        )
        val offsetHours = decidedAtZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        val decidedAtFormatted = "${decidedAtZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"

        // Yacht reference per Mario rule: Manufacturer + Model + Name.
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModel = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModel, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val pickupDateHour = "${reservation.dateFrom!!.format(dateFormatter)}<br/>${reservation.dateFrom!!.format(hourFormatter)}"
        val dropOffDateHourPeriod =
            "${reservation.dateTo!!.format(dateFormatter)}<br/>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}"

        val pickupLocation = reservation.locationFrom!!.name ?: ""
        val pickupCountry = reservation.locationFrom!!.country?.name ?: ""

        val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${flow.email!!}>"
            else flow.email!!

        val customerLocale = resolveEmailLocale(user.language)

        val customerVariables = mapOf(
            "fullName" to fullName,
            "reservationId" to displayReservationRef,
            "yachtFullLabel" to yachtFullLabel,
            "yachtImageUrl" to yachtImageUrl,
            "pickupLocation" to pickupLocation,
            "pickupCountry" to pickupCountry,
            "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
            "pickupDateHour" to pickupDateHour,
            "dropOffDateHourPeriod" to dropOffDateHourPeriod,
            "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
            "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
            "decidedAt" to decidedAtFormatted,
            "currentYear" to LocalDate.now().year.toString(),
        )
        val customerSubject = messageSource.getMessage(
            "cancellationApproved.subject",
            arrayOf<Any>(displayReservationRef),
            customerLocale,
        )

        // Best-effort email — the cancellation has already been committed in
        // a separate transaction by the time this fires, so a mail-side
        // failure must not throw.
        try {
            emailService.sendEmail(
                recipients = listOf(recipientAddress),
                subject = customerSubject,
                templateName = "email/cancellationApproved",
                variables = customerVariables,
                locale = customerLocale,
            )
        } catch (e: Exception) {
            org.slf4j.LoggerFactory
                .getLogger(this::class.java.name)
                .error("Failed to send customer cancellation-approved email for reservation $displayReservationRef", e)
        }
    }

    /**
     * Pre-charter reminder fired by [PreCharterReminderJob] one day before
     * `dateFrom`. Goal: cut no-shows + give the customer a friendly checklist
     * (passport, sailing licence, what to pack) plus the agency contact for
     * last-minute questions. No financial detail (refund/deposit copy stays
     * out — Mario rule). Idempotency is best-effort: the daily cron filters
     * to dateFrom = tomorrow + status RESERVATION, so a single duplicate run
     * is the only practical risk.
     */
    @Transactional(readOnly = true)
    fun sendPreCharterReminder(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val flow = reservation.reservationFlow!!
        val yacht = flow.yacht!!
        val user = flow.user!!

        val yachtImageUrl = yacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
        // "Sent {date · time (GMT±X)}" — Europe/Zagreb wall-clock matches the
        // header style used by the cancellation emails so all transactional
        // mail reads consistently.
        val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
        val sentAtZoned = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Zagreb"))
        val offsetHours = sentAtZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        val sentAtFormatted = "${sentAtZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"

        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModel = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModel, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val pickupDateHour = "${reservation.dateFrom!!.format(dateFormatter)}<br/>${reservation.dateFrom!!.format(hourFormatter)}"
        val dropOffDateHourPeriod =
            "${reservation.dateTo!!.format(dateFormatter)}<br/>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}"

        val pickupLocation = reservation.locationFrom!!.name ?: ""
        val pickupCountry = reservation.locationFrom!!.country?.name ?: ""

        val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
        val recipientAddress =
            if (fullName != "there") "$fullName <${flow.email!!}>"
            else flow.email!!

        val customerLocale = resolveEmailLocale(user.language)

        val customerVariables = mapOf(
            "fullName" to fullName,
            "reservationId" to displayReservationRef,
            "yachtFullLabel" to yachtFullLabel,
            "yachtImageUrl" to yachtImageUrl,
            "pickupLocation" to pickupLocation,
            "pickupCountry" to pickupCountry,
            "viewBoatUrl" to serverHostPublic + "/boat/" + yacht.id,
            "pickupDateHour" to pickupDateHour,
            "dropOffDateHourPeriod" to dropOffDateHourPeriod,
            "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(reservation.locationFrom!!.name, Charsets.UTF_8)}",
            "reservationUrl" to serverHostPublic + "/my-bookings/" + reservation.id,
            "sentAt" to sentAtFormatted,
            "currentYear" to LocalDate.now().year.toString(),
        )
        val customerSubject = messageSource.getMessage(
            "preCharterReminder.subject",
            arrayOf<Any>(displayReservationRef),
            customerLocale,
        )

        try {
            emailService.sendEmail(
                recipients = listOf(recipientAddress),
                subject = customerSubject,
                templateName = "email/preCharterReminder",
                variables = customerVariables,
                locale = customerLocale,
            )
        } catch (e: Exception) {
            org.slf4j.LoggerFactory
                .getLogger(this::class.java.name)
                .error("Failed to send pre-charter reminder for reservation $displayReservationRef", e)
        }
    }

    fun sendYachtSwapNotification(
        reservation: Reservation,
        audit: ReservationYachtSwapAudit,
    ) {
        val flow = reservation.reservationFlow ?: return
        val newYacht = flow.yacht ?: return
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"
        val yachtImageUrl = newYacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }

        val customerMessage =
            when (audit.action) {
                YachtSwapAction.AUTO_UPDATED ->
                    "The charter agency has replaced the yacht on your reservation $displayReservationRef. " +
                        "Your new yacht is shown below. Dates, location and price remain the same. " +
                        "Please review the new yacht details and contact us if anything looks wrong."
                YachtSwapAction.MANUAL_REVIEW ->
                    "The charter agency has changed the yacht on your reservation $displayReservationRef. " +
                        "We're verifying the new yacht details and will contact you shortly. " +
                        "No action required from you right now."
                else ->
                    "A change was detected on your reservation $displayReservationRef. " +
                        "Our team is reviewing and will contact you shortly."
            }

        val customerVariables =
            mapOf(
                "reservationId" to displayReservationRef,
                "message" to customerMessage,
                "reservationUrl" to "$serverHostPublic/my-bookings/${reservation.id}",
                "yachtImageUrl" to yachtImageUrl,
                "yachtModel" to newYacht.model?.name,
                "yachtName" to newYacht.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to "$serverHostPublic/boat/${newYacht.id}",
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = listOf(flow.email!!),
            subject = "Your yacht has been replaced on reservation $displayReservationRef",
            templateName = "email/yachtReplaced",
            variables = customerVariables,
        )

        val adminEmails = userRepository.findAllAdminEmailAddresses()
        val adminMessage =
            "Yacht-swap detected on reservation $displayReservationRef. " +
                "Action: ${audit.action?.name}. " +
                "Previous yacht id: ${audit.previousYachtId} (external: ${audit.previousExternalYachtId}). " +
                "New yacht id: ${audit.newYachtId ?: "UNKNOWN"} (external: ${audit.newExternalYachtId}). " +
                "External system id: ${audit.externalSystemId}. " +
                (audit.notes?.let { "Notes: $it" } ?: "")

        val adminVariables =
            mapOf(
                "reservationId" to displayReservationRef,
                "message" to adminMessage,
                "reservationUrl" to "$serverHostAdminPublic/bookings/$displayReservationRef",
                "yachtImageUrl" to yachtImageUrl,
                "yachtModel" to newYacht.model?.name,
                "yachtName" to newYacht.name,
                "locationFrom" to reservation.locationFrom!!.name,
                "viewBoatUrl" to "$serverHostPublic/boat/${newYacht.id}",
                "publicUrl" to serverHostPublic,
            )

        emailService.sendEmail(
            recipients = adminEmails,
            subject = "[ADMIN] Yacht-swap detected on $displayReservationRef (${audit.action?.name})",
            templateName = "email/yachtReplaced",
            variables = adminVariables,
        )
    }
}
