package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

@Service
class PaymentPendingNotificationService(
    private val paymentPhasesRepository: ReservationPaymentPhaseRepository,
    private val reservationRepository: ReservationRepository,
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    private val settingsService: hr.workspace.boat4you.domains.settings.services.AdminSettingsService,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
    private val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
    // Header timestamp format — matches optionExpiryReminder / fewMoreDetails
    // so the customer sees a single timestamp style across the booking flow.
    private val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")

    /** Format `Apr 24, 2026 · 11:12 (GMT+2)` in Europe/Zagreb. Mirrors the
     *  helper in `OptionExpiryService` so the reminder header timestamp
     *  reads identically across email types. */
    private fun formatReceivedAt(): String {
        val nowZoned = ZonedDateTime.now(ZoneId.of("Europe/Zagreb"))
        val offsetHours = nowZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        return "${nowZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"
    }

    /**
     * Bundle holding everything needed to render the payment-pending
     * reminder template + dispatch the email. Mirrors
     * `OptionExpiryService.ReminderContext` so the cron-job pattern
     * (build-or-skip → send) stays consistent across job classes.
     */
    private data class PendingContext(
        val variables: Map<String, Any?>,
        val recipientAddress: String,
        val customerLocale: Locale,
        val displayReservationRef: String,
        val daysInAdvance: Long,
    )

    /** Localised "Per <unit>" suffix for an [ExtrasUnitType]. Reads from
     *  the shared `extrasUnit.*` resource bundle so the payment-pending
     *  reminder stays aligned with the wording in fewMoreDetails /
     *  reservationConfirmed / optionExpiryReminder. Returns empty string
     *  for unknown / null units. Duplicated verbatim from
     *  `OptionExpiryService.unitLabel` — extracting to a shared helper is
     *  a future cleanup; the duplication keeps the cron-side services
     *  decoupled. */
    private fun unitLabel(
        unit: hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType?,
        loc: Locale,
    ): String {
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

    /**
     * Build the variables map + email envelope for a payment-pending
     * reminder. Returns null when the reservation is missing data we
     * can't email around (yacht / email / location). Caller logs +
     * skips when null so a single broken row doesn't kill the batch.
     *
     * Mirrors `OptionExpiryService.buildReminderVariables` 1:1 with
     * the obvious differences:
     *   - sources the upcoming installment from the [pendingPaymentPhase]
     *     row supplied by the caller (NOT "first unpaid"), since the
     *     job fires per-deadline and the customer cares about THIS
     *     specific upcoming wire transfer
     *   - exposes [daysInAdvance] + [dueIn] for the hero copy
     *   - earlier phases ARE paid (deposit at minimum) — Mario takes
     *     the deposit before the reservation transitions to BOOKING —
     *     so the Terms-of-payment list renders the green strike-through
     *     + "Paid" pill on already-settled rows
     */
    private fun buildPendingVariables(
        reservation: Reservation,
        pendingPaymentPhase: ReservationPaymentPhase,
        daysInAdvance: Long,
    ): PendingContext? {
        val flow = reservation.reservationFlow
        val yacht = flow?.yacht
        val email = flow?.email
        val locationFrom = reservation.locationFrom
        if (flow == null || yacht == null || email.isNullOrBlank() || locationFrom == null) {
            log.warn(
                "Skipping payment-pending email for reservation={} — missing data " +
                    "(flow={}, yacht={}, email blank={}, loc={})",
                reservation.id, flow?.id, yacht?.id, email.isNullOrBlank(), locationFrom?.id,
            )
            return null
        }

        // Locale must come from `user.language` — the cron job runs outside
        // any HTTP request scope so `LocaleContextHolder` would just return
        // the JVM default. Falls back to English when the user has no
        // language set (legacy rows / admin-created reservations).
        val customerLocale = resolveEmailLocale(flow.user?.language)

        // Full name — prefer the linked user record, fall back to the flow's
        // own copy (filled at booking time for guest checkouts), finally
        // "there" if both are blank.
        val fullName = (
            flow.user?.getFullName()?.trim()?.takeIf { it.isNotBlank() }
                ?: flow.getFullName().trim().takeIf { it.isNotBlank() }
                ?: "there"
            )
        val recipientAddress =
            if (fullName != "there") "$fullName <$email>" else email

        // Yacht reference per Mario rule: Manufacturer + Model + Name.
        val yachtManufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
        val yachtModelName = yacht.model?.name
        val yachtName = yacht.name
        val yachtFullLabel = listOfNotNull(yachtManufacturer, yachtModelName, yachtName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { yachtName ?: "—" }

        val yachtImageUrl = yacht.mainImageId?.let { "$serverHost/public/image/$it?width=936" }
        val displayReservationRef = reservation.reservationNumber ?: "${reservation.id!!}"

        val currencySymbol = Currency
            .getInstance(reservation.currency)
            .getSymbol(Locale.getDefault())
            .toString()
        val totalPriceLabel = "${(reservation.totalPrice ?: BigDecimal.ZERO).toPlainString()}$currencySymbol"

        // Payment phases — render BOTH paid and unpaid rows. Booking is
        // already CONFIRMED so the deposit (and possibly mid-balance)
        // are settled; the green strike-through + "Paid" pill mirror
        // fewMoreDetails / reservationConfirmed exactly.
        val sortedPhases = flow.paymentPhases.sortedBy { it.deadline }
        val phaseViews: List<Map<String, Any?>> = sortedPhases.mapIndexed { idx, p ->
            val ordKey = when {
                idx == sortedPhases.size - 1 && sortedPhases.size > 1 -> "paymentPending.phaseFinal"
                idx == 0 -> "paymentPending.phaseFirst"
                idx == 1 -> "paymentPending.phaseSecond"
                else -> "paymentPending.phaseNth"
            }
            val ordLabel = runCatching {
                messageSource.getMessage(ordKey, arrayOf<Any>(idx + 1), customerLocale)
            }.getOrDefault("Payment ${idx + 1}")
            mapOf(
                "label" to ordLabel,
                "amountLabel" to "${p.amount.toPlainString()}$currencySymbol",
                "deadlineLabel" to p.deadline.format(dateFormatter),
                "isPaid" to (p.paidOn != null),
            )
        }
        // The bank-transfer card targets THIS specific deadline (not "first
        // unpaid") — we run the cron per-deadline (1d/3d before the date),
        // and the customer needs the wire-amount + due-date that match the
        // installment they're being reminded about. The wire amount carries
        // this installment's share of the fixed bank-transfer fee (same math
        // as sendOptionCreatedEmail / the payment page).
        val bankFeeTotal = settingsService.getSetting(
            hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum.BANK_TRANSFER_FIXED_FEE,
        ).value?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val pendingPhaseIdx = sortedPhases.indexOfFirst { it.id == pendingPaymentPhase.id }.coerceAtLeast(0)
        val bankFeeShare = hr.workspace.boat4you.domains.reservation.service.BankTransferFeeShare
            .shareFor(bankFeeTotal, sortedPhases.size.coerceAtLeast(1), pendingPhaseIdx)
        val dueNowLabel = "${(pendingPaymentPhase.amount + bankFeeShare).toPlainString()}$currencySymbol"
        val dueNowDeadlineLabel = pendingPaymentPhase.deadline.format(dateFormatter)
        val bankFeeNoticeLabel = if (bankFeeShare > BigDecimal.ZERO) {
            runCatching {
                messageSource.getMessage(
                    "fewMoreDetails.bankFeeNotice",
                    arrayOf<Any>("${bankFeeShare.toPlainString()}$currencySymbol"),
                    customerLocale,
                )
            }.getOrNull()
        } else {
            null
        }

        // Extras — same split as fewMoreDetails / optionExpiryReminder so
        // the customer sees the full picture: what's locked into the wire
        // payment (in-base), what they'll settle on-site, and what's still
        // browsable from the yacht's catalogue at pickup.
        val resvExtras = flow.reservationExtras
        fun extraToView(e: hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra): Map<String, Any?> {
            val priceVal = e.price ?: BigDecimal.ZERO
            return mapOf(
                "name" to (e.name ?: "—"),
                "priceLabel" to "${priceVal.toPlainString()}$currencySymbol",
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
                    "priceLabel" to "${(ye.price ?: BigDecimal.ZERO).toPlainString()}$currencySymbol",
                    "unitLabel" to unitLabel(ye.unit, customerLocale),
                    "obligatory" to (ye.obligatory == true),
                )
            }

        // "in 3 days" / "tomorrow" — localised hero subtitle phrasing. We
        // pass a singular vs plural key so each language can phrase the
        // 1-day case naturally (e.g. HR "sutra", DE "morgen") instead of
        // an English-shaped "in 1 day".
        val dueIn = runCatching {
            val key = if (daysInAdvance == 1L) "paymentPending.dueInOne" else "paymentPending.dueInMany"
            messageSource.getMessage(key, arrayOf<Any>(daysInAdvance), customerLocale)
        }.getOrDefault(if (daysInAdvance == 1L) "tomorrow" else "in $daysInAdvance days")

        val variables = mapOf<String, Any?>(
            "fullName" to fullName,
            "daysInAdvance" to daysInAdvance,
            "dueIn" to dueIn,
            "reservationId" to displayReservationRef,
            "yachtImageUrl" to yachtImageUrl,
            "yachtFullLabel" to yachtFullLabel,
            "yachtName" to yachtName,
            "yachtModel" to yachtModelName,
            "locationFrom" to locationFrom.name,
            "viewBoatUrl" to "$serverHostPublic/boat/${yacht.id}",
            "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br/>${reservation.dateFrom!!.format(hourFormatter)}",
            "dropOffDateHourPeriod" to
                "${reservation.dateTo!!.format(dateFormatter)}<br/>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
            "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(locationFrom.name, Charsets.UTF_8)}",
            "pickupLocation" to locationFrom.name,
            "totalPrice" to totalPriceLabel,
            "dueNowLabel" to dueNowLabel,
            "dueNowDeadlineLabel" to dueNowDeadlineLabel,
            "bankFeeNoticeLabel" to bankFeeNoticeLabel,
            "paymentPhases" to phaseViews,
            "extrasInBase" to extrasInBase,
            "extrasOnSite" to extrasOnSite,
            "availableAtMarina" to availableAtMarina,
            "reservationUrl" to "$serverHostPublic/my-bookings/${reservation.id}",
            "publicUrl" to serverHostPublic,
            "tncUrl" to "$serverHostPublic/terms-and-conditions",
            "receivedAt" to formatReceivedAt(),
            "currentYear" to LocalDate.now().year.toString(),
        )

        return PendingContext(
            variables = variables,
            recipientAddress = recipientAddress,
            customerLocale = customerLocale,
            displayReservationRef = displayReservationRef,
            daysInAdvance = daysInAdvance,
        )
    }

    @Transactional(readOnly = true)
    fun sendPaymentReminder(daysInAdvance: Long) {
        val pendingPayments = paymentPhasesRepository.findPendingPayments(LocalDate.now().plusDays(daysInAdvance))
        if (pendingPayments.isEmpty()) {
            log.info("No pending payments found")
            return
        }

        val flowIdToReservationMap = reservationRepository
            .findByReservationFlowIdIn(pendingPayments.map { it.reservationFlow.id!! })
            .associateBy { it.reservationFlow!!.id!! }

        var skipped = 0
        var sent = 0
        pendingPayments.forEach { pendingPayment ->
            // Defensive guards: each lookup used `!!` and a single missing
            // reservation/yacht/email row used to throw NPE inside this
            // forEach, aborting the WHOLE batch — every customer past the
            // failure point lost their reminder until the next cron tick.
            // Skip-with-warn keeps the rest of the batch alive.
            try {
                val flow = pendingPayment.reservationFlow
                val reservation = flowIdToReservationMap[flow.id]
                if (reservation == null) {
                    log.warn(
                        "Skipping payment-pending email for flow={} — no matching reservation",
                        flow.id,
                    )
                    skipped++
                    return@forEach
                }
                val ctx = buildPendingVariables(reservation, pendingPayment, daysInAdvance)
                if (ctx == null) {
                    skipped++
                    return@forEach
                }
                val subject = messageSource.getMessage(
                    "paymentPending.subject",
                    arrayOf<Any>(daysInAdvance, ctx.displayReservationRef),
                    ctx.customerLocale,
                )
                emailService.sendEmail(
                    recipients = listOf(ctx.recipientAddress),
                    subject = subject,
                    templateName = "email/reservationPaymentPending",
                    variables = ctx.variables,
                    locale = ctx.customerLocale,
                )
                sent++
            } catch (e: Exception) {
                log.error(
                    "Failed to send payment-pending email for flow={} — continuing batch",
                    pendingPayment.reservationFlow.id, e,
                )
                skipped++
            }
        }
        log.info("Payment-pending reminder batch: sent=$sent, skipped=$skipped, total=${pendingPayments.size}")
    }
}
