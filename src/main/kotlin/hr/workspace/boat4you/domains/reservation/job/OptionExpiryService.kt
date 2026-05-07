package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale

@Service
class OptionExpiryService(
    private val emailService: EmailService,
    private val reservationRepository: ReservationRepository,
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val messageSource: MessageSource,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")
    private val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")
    // Match fewMoreDetails / reservationConfirmed for `optionExpiresAt` —
    // `dd MMMM yyyy 'at' HH:mm` so the customer sees the exact partner
    // deadline in the same shape as in the option-created email.
    private val expiryFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy 'at' HH:mm")
    // Header timestamp format — matches inquiry / fewMoreDetails so the
    // customer sees a single timestamp style across the booking flow.
    private val receivedFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")

    /**
     * Total partner-granted option window — `optionExpiresAt - createdAt`.
     * Returns null if either timestamp is missing.
     *
     * Used as a guard so we don't send a "24h before expiry" reminder on an
     * option that only ever lasted 6h (the reminder would either fall before
     * the option existed or land in the same hour as the option email itself,
     * which is noise). MMK / NauSys grant wildly different windows depending
     * on charter lead time — short-lead bookings can get an option as small
     * as a few hours, long-lead bookings get a week. The reminder schedule
     * has to bracket that.
     */
    private fun optionDuration(reservation: Reservation): Duration? {
        val expiresAt = reservation.optionExpiresAt ?: return null
        // Partner-side creation timestamp is the truthful start of the option
        // window (matches what MMK / NauSys saw when they computed
        // expirationDate / optionTill). Fall back to our local createdAt
        // (Instant) only when the partner field is missing — same wall-clock
        // for all practical purposes, but the explicit conversion keeps types
        // honest.
        val createdAt = reservation.externalCreatedAt
            ?: reservation.createdAt?.atZone(ZoneId.systemDefault())?.toLocalDateTime()
            ?: return null
        return Duration.between(createdAt, expiresAt)
    }

    /** Format `Apr 24, 2026 · 11:12 (GMT+2)` in Europe/Zagreb. */
    private fun formatReceivedAt(): String {
        val nowZoned = ZonedDateTime.now(ZoneId.of("Europe/Zagreb"))
        val offsetHours = nowZoned.offset.totalSeconds / 3600
        val sign = if (offsetHours >= 0) "+" else "-"
        return "${nowZoned.format(receivedFormatter)} (GMT$sign${kotlin.math.abs(offsetHours)})"
    }

    /**
     * Bundle holding everything needed to render the reminder template +
     * dispatch the email. Lifted out of the per-method copy-paste so the
     * 24h / 48h / 72h reminders share locale resolution, RFC2822 formatting,
     * payment-phase view-modeling and yacht-label formatting.
     */
    private data class ReminderContext(
        val variables: Map<String, Any?>,
        val recipientAddress: String,
        val customerLocale: Locale,
        val displayReservationRef: String,
    )

    /**
     * Build the variables map + email envelope for an option-expiry reminder
     * (24h / 48h / 72h variant — only `expiryHours` differs between them).
     * Returns null when the reservation is missing data we can't email
     * around (yacht / email / location / option deadline). Caller logs +
     * skips when null so a single broken row doesn't kill the batch.
     */
    /** Localised "Per <unit>" suffix for an [ExtrasUnitType]. Reads from
     *  the shared `extrasUnit.*` resource bundle so reminders stay aligned
     *  with the wording in fewMoreDetails / reservationConfirmed. Returns
     *  empty string for unknown / null units. */
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

    private fun buildReminderVariables(
        reservation: Reservation,
        expiryHours: String,
    ): ReminderContext? {
        val flow = reservation.reservationFlow
        val yacht = flow?.yacht
        val email = flow?.email
        val locationFrom = reservation.locationFrom
        val expiresAt = reservation.optionExpiresAt
        if (flow == null || yacht == null || email.isNullOrBlank() || locationFrom == null || expiresAt == null) {
            log.warn(
                "Skipping option-expiry email for reservation={} — missing data " +
                    "(flow={}, yacht={}, email blank={}, loc={}, expiresAt={})",
                reservation.id, flow?.id, yacht?.id, email.isNullOrBlank(), locationFrom?.id, expiresAt,
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

        // Payment phases — same shape and ordinal-key resolution as
        // `sendOptionCreatedEmail` but under the `optionReminder.*`
        // namespace so localisation can diverge if Mario ever wants
        // different phrasing in the reminder.
        val sortedPhases = flow.paymentPhases.sortedBy { it.deadline }
        val phaseViews: List<Map<String, Any?>> = sortedPhases.mapIndexed { idx, p ->
            val ordKey = when {
                idx == sortedPhases.size - 1 && sortedPhases.size > 1 -> "optionReminder.phaseFinal"
                idx == 0 -> "optionReminder.phaseFirst"
                idx == 1 -> "optionReminder.phaseSecond"
                else -> "optionReminder.phaseNth"
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
        val firstUnpaid = sortedPhases.firstOrNull { it.paidOn == null }
        val dueNowLabel = firstUnpaid?.let { "${it.amount.toPlainString()}$currencySymbol" } ?: totalPriceLabel
        val dueNowDeadlineLabel = firstUnpaid?.deadline?.format(dateFormatter) ?: ""

        // Extras — same split as fewMoreDetails so the reminder shows the
        // customer the full picture: what's locked into the wire payment,
        // what they'll settle on-site, and what's still browsable from the
        // yacht's catalogue at pickup. Mario rule (3.5.2026): the reminder
        // must carry the same context as the option-created email so the
        // customer doesn't have to dig through the inbox to remember why
        // they're paying X EUR.
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

        val variables = mapOf<String, Any?>(
            "fullName" to fullName,
            "expiryHours" to expiryHours,
            "reservationId" to displayReservationRef,
            "yachtImageUrl" to yachtImageUrl,
            "yachtFullLabel" to yachtFullLabel,
            "yachtName" to yachtName,
            "yachtModel" to yachtModelName,
            "locationFrom" to locationFrom.name,
            "viewBoatUrl" to "$serverHostPublic/boat/${yacht.id}",
            "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br>${reservation.dateFrom!!.format(hourFormatter)}",
            "dropOffDateHourPeriod" to
                "${reservation.dateTo!!.format(dateFormatter)}<br>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
            "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(locationFrom.name, Charsets.UTF_8)}",
            "pickupLocation" to locationFrom.name,
            "totalPrice" to totalPriceLabel,
            "dueNowLabel" to dueNowLabel,
            "dueNowDeadlineLabel" to dueNowDeadlineLabel,
            "paymentPhases" to phaseViews,
            "extrasInBase" to extrasInBase,
            "extrasOnSite" to extrasOnSite,
            "availableAtMarina" to availableAtMarina,
            "expiryDate" to expiresAt.format(expiryFormatter),
            "reservationUrl" to "$serverHostPublic/my-bookings/${reservation.id}",
            "publicUrl" to serverHostPublic,
            "tncUrl" to "$serverHostPublic/terms-and-conditions",
            "receivedAt" to formatReceivedAt(),
            "currentYear" to LocalDate.now().year.toString(),
        )

        return ReminderContext(
            variables = variables,
            recipientAddress = recipientAddress,
            customerLocale = customerLocale,
            displayReservationRef = displayReservationRef,
        )
    }

    /**
     * Build the variables map + email envelope for the option-expired
     * notification. Smaller surface than the reminder (no bank details, no
     * payment schedule) — just enough for the customer to see what was
     * held + a re-engagement nudge.
     */
    private fun buildExpiredVariables(reservation: Reservation): ReminderContext? {
        val flow = reservation.reservationFlow
        val yacht = flow?.yacht
        val email = flow?.email
        val locationFrom = reservation.locationFrom
        if (flow == null || yacht == null || email.isNullOrBlank() || locationFrom == null) {
            log.warn(
                "Skipping option-expired email for reservation={} — missing data " +
                    "(flow={}, yacht={}, email blank={}, loc={})",
                reservation.id, flow?.id, yacht?.id, email.isNullOrBlank(), locationFrom?.id,
            )
            return null
        }

        val customerLocale = resolveEmailLocale(flow.user?.language)
        val fullName = (
            flow.user?.getFullName()?.trim()?.takeIf { it.isNotBlank() }
                ?: flow.getFullName().trim().takeIf { it.isNotBlank() }
                ?: "there"
            )
        val recipientAddress =
            if (fullName != "there") "$fullName <$email>" else email

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

        // Payment schedule + extras — same shape as the reminder. Mario rule
        // (3.5.2026): the expired email also recaps the booking the customer
        // walked away from, including the obligatory extras they would've
        // settled at the marina. Helps the customer make sense of what was
        // booked when they re-engage from the CTA further down.
        val sortedPhases = flow.paymentPhases.sortedBy { it.deadline }
        val phaseViews: List<Map<String, Any?>> = sortedPhases.mapIndexed { idx, p ->
            val ordKey = when {
                idx == sortedPhases.size - 1 && sortedPhases.size > 1 -> "optionReminder.phaseFinal"
                idx == 0 -> "optionReminder.phaseFirst"
                idx == 1 -> "optionReminder.phaseSecond"
                else -> "optionReminder.phaseNth"
            }
            val ordLabel = runCatching {
                messageSource.getMessage(ordKey, arrayOf<Any>(idx + 1), customerLocale)
            }.getOrDefault("Payment ${idx + 1}")
            mapOf(
                "label" to ordLabel,
                "amountLabel" to "${p.amount.toPlainString()}$currencySymbol",
                "deadlineLabel" to p.deadline.format(dateFormatter),
                "isPaid" to false, // expired flow: nothing was settled
            )
        }

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

        val variables = mapOf<String, Any?>(
            "fullName" to fullName,
            "reservationId" to displayReservationRef,
            "yachtImageUrl" to yachtImageUrl,
            "yachtFullLabel" to yachtFullLabel,
            "yachtName" to yachtName,
            "yachtModel" to yachtModelName,
            "locationFrom" to locationFrom.name,
            "viewBoatUrl" to "$serverHostPublic/boat/${yacht.id}",
            "pickupDateHour" to "${reservation.dateFrom!!.format(dateFormatter)}<br>${reservation.dateFrom!!.format(hourFormatter)}",
            "dropOffDateHourPeriod" to
                "${reservation.dateTo!!.format(dateFormatter)}<br>${reservation.dateTo!!.minusHours(1)!!.format(hourFormatter)}-${reservation.dateTo!!.format(hourFormatter)}",
            "locationUrl" to "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(locationFrom.name, Charsets.UTF_8)}",
            "pickupLocation" to locationFrom.name,
            "totalPrice" to totalPriceLabel,
            "paymentPhases" to phaseViews,
            "extrasInBase" to extrasInBase,
            "extrasOnSite" to extrasOnSite,
            "availableAtMarina" to availableAtMarina,
            "publicUrl" to serverHostPublic,
            "tncUrl" to "$serverHostPublic/terms-and-conditions",
            "receivedAt" to formatReceivedAt(),
            "currentYear" to LocalDate.now().year.toString(),
        )

        return ReminderContext(
            variables = variables,
            recipientAddress = recipientAddress,
            customerLocale = customerLocale,
            displayReservationRef = displayReservationRef,
        )
    }

    private fun sendReminderBatch(
        reservations: List<Reservation>,
        expiryHours: String,
        logTag: String,
    ) {
        if (reservations.isEmpty()) {
            log.info("No users with $logTag-expiring options found.")
            return
        }
        var skipped = 0
        reservations.forEach { reservation ->
            try {
                val ctx = buildReminderVariables(reservation, expiryHours)
                if (ctx == null) {
                    skipped++
                    return@forEach
                }
                val subject = messageSource.getMessage(
                    "optionReminder.subject",
                    arrayOf<Any>(expiryHours, ctx.displayReservationRef),
                    ctx.customerLocale,
                )
                emailService.sendEmail(
                    recipients = listOf(ctx.recipientAddress),
                    subject = subject,
                    templateName = "email/optionExpiryReminder",
                    variables = ctx.variables,
                    locale = ctx.customerLocale,
                )
            } catch (e: Exception) {
                log.error("Failed $logTag option-expiry email for reservation={} — continuing batch", reservation.id, e)
                skipped++
            }
        }
        if (skipped > 0) {
            log.info("$logTag option-expiry batch finished: total=${reservations.size}, skipped=$skipped")
        }
    }

    @Transactional(readOnly = true)
    fun send24HourOptionExpirationReminder() {
        val startTime = LocalDateTime.now().plusHours(23)
        val endTime = LocalDateTime.now().plusHours(24)

        val reservations = reservationRepository
            .findExpiringReservations(startTime, endTime, OfferStatus.OPTION)
            // Skip options shorter than the reminder window — sending a "24h
            // before expiry" notice on a 12h-long option creates noise (the
            // reminder lands in the same hour as the option-created email,
            // before the customer has even seen the original mail).
            .filter { (optionDuration(it)?.toHours() ?: Long.MAX_VALUE) >= 24 }

        sendReminderBatch(reservations, "24h", "24h")
    }

    /**
     * "Midway" reminder ~72h before option expires — fires only for LONG
     * options (≥ 4 days = 96h total window). Without it, a 6-day option
     * would have ~4 days of silence between the creation email and the 48h
     * reminder, increasing the risk that the customer forgets. Mario
     * decision 1.5.2026: "dodaj srednju opciju" za opcije ≥ 4 dana.
     *
     * 72h chosen so the cadence stays clean: 72h → 48h → 24h → cancel.
     */
    @Transactional(readOnly = true)
    fun send72HourOptionExpirationReminder() {
        val startTime = LocalDateTime.now().plusHours(71)
        val endTime = LocalDateTime.now().plusHours(72)

        val reservations = reservationRepository
            .findExpiringReservations(startTime, endTime, OfferStatus.OPTION)
            // Only long options — 4-day+ window. Shorter options would
            // collapse this reminder onto the creation email (3-day option
            // = 72h, so 72h-before-expiry == creation moment). Plus we
            // skip the noise for medium options that already get 48h+24h.
            .filter { (optionDuration(it)?.toHours() ?: Long.MAX_VALUE) >= 96 }

        sendReminderBatch(reservations, "72h", "72h")
    }

    @Transactional(readOnly = true)
    fun send48HourOptionExpirationReminder() {
        val startTime = LocalDateTime.now().plusHours(47)
        val endTime = LocalDateTime.now().plusHours(48)

        val reservations = reservationRepository
            .findExpiringReservations(startTime, endTime, OfferStatus.OPTION)
            // See 24h variant for rationale — only fire on options whose
            // partner-granted window is at least as long as the reminder
            // distance (otherwise the reminder couldn't possibly fit).
            .filter { (optionDuration(it)?.toHours() ?: Long.MAX_VALUE) >= 48 }

        sendReminderBatch(reservations, "48h", "48h")
    }

    /**
     * Hours after `optionExpiresAt` past which we auto-cancel locally even if
     * the partner hasn't flipped the status yet. 6 hours = generous grace
     * window for last-minute customer wires + partner reconciliation lag, but
     * short enough that the customer dashboard doesn't show "OPTION pending"
     * forever when the option is realistically dead.
     *
     * Mario decision 1.5.2026: "ako se rezervacija ne dogodi i mi ne kliknemo
     * da je potvrdjen booking, hoce li u adminu automatski otici u cancelled?
     * to bi trebalo implementirati."
     */
    private val autoCancelGraceHours: Long = 6

    @Transactional(readOnly = false)
    fun syncExpiredOptions() {
        val now = LocalDateTime.now()
        val expiredReservations =
            reservationRepository.findAllBySysStatusAndOptionExpiresAtBefore(
                ReservationStatus.OPTION,
                now,
            )
        log.trace("Checking status for expired reservations: {}", expiredReservations.size)
        expiredReservations.forEach { reservation ->
            // Step 1: try to sync from partner. If partner has already flipped
            // (cancelled / converted to booking), trust it and mirror locally.
            val partnerSynced = runCatching {
                val extReservation = reservationIntegrationService.getExternalReservation(reservation.id!!)
                if (extReservation.calculatedSysStatus != ReservationStatus.OPTION) {
                    log.info("Partner flipped reservation {} → {}", reservation.id, extReservation.calculatedSysStatus)
                    reservationMutationService.refreshReservation(reservation.id!!, extReservation)
                    sendExpiredEmail(reservation)
                    return@runCatching true
                }
                false
            }.getOrElse { e ->
                log.warn("Partner sync failed for reservation ${reservation.id} — falling through to grace-period check", e)
                false
            }

            if (partnerSynced) return@forEach

            // Step 2: partner still says OPTION (or sync failed). If we've crossed
            // the grace window since `optionExpiresAt`, we auto-cancel locally so
            // the customer dashboard + admin list reflect reality instead of
            // showing a stale OPTION row indefinitely.
            val expiresAt = reservation.optionExpiresAt ?: return@forEach
            val hoursOverdue = ChronoUnit.HOURS.between(expiresAt, now)
            if (hoursOverdue >= autoCancelGraceHours) {
                log.info(
                    "Auto-cancelling reservation {} — option expired {}h ago, partner still OPTION",
                    reservation.id, hoursOverdue,
                )
                reservation.sysStatus = ReservationStatus.CANCELLED
                reservationRepository.save(reservation)
                // Cancellation reason lives on ReservationFlow (legacy spelling
                // `cancelation_request` in DB). Dirty-check inside the @Transactional
                // boundary flushes the flow update at commit; no cascade on
                // Reservation.reservationFlow so we touch the field directly.
                reservation.reservationFlow?.let { flow ->
                    // `[SYSTEM]` prefix matches the existing `[AGENT]`/`[ADMIN]`
                    // convention in ReservationHeroSection.tsx — frontend strips
                    // the marker and renders a dedicated "auto-cancelled, payment
                    // not received" banner instead of the customer-facing reason
                    // path. See FE handling around line 90 (agentMarkerLen).
                    flow.cancelationRequest = "[SYSTEM] Payment not received within the option deadline."
                    flow.cancelationRequestAt = now
                }
                sendExpiredEmail(reservation)
            } else {
                log.trace(
                    "Reservation {} is still in OPTION status (overdue {}h, grace {}h)",
                    reservation.id, hoursOverdue, autoCancelGraceHours,
                )
            }
        }
    }

    private fun sendExpiredEmail(reservation: Reservation) {
        val ctx = buildExpiredVariables(reservation) ?: return
        try {
            val subject = messageSource.getMessage(
                "optionExpired.subject",
                arrayOf<Any>(ctx.displayReservationRef),
                ctx.customerLocale,
            )
            emailService.sendEmail(
                recipients = listOf(ctx.recipientAddress),
                subject = subject,
                templateName = "email/optionExpired",
                variables = ctx.variables,
                locale = ctx.customerLocale,
            )
        } catch (e: Exception) {
            log.error("Failed option-expired email for reservation={}", reservation.id, e)
        }
    }
}
