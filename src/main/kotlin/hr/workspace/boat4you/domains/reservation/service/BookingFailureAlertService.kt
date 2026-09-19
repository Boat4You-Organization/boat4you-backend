package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells the admins, by e-mail, that a customer tried to book online and got an error.
 *
 * 19.9.2026: a partner extra name over a column width failed every booking on one yacht; the customer retried 8 times
 * in 14 minutes and we only found out because he wrote to us. A failed booking is the most expensive event on the
 * platform and we already know who it was - this makes sure a human sees it while the customer is still reachable.
 *
 * Two kinds, told apart in the subject: a SYSTEM failure (ours - the 19.9. class) and a PARTNER rejection (the yacht
 * was not available at the partner after all). The second is not an incident, but at this booking volume a customer
 * who just tried to pay for a week is a lead worth an alternative offer the same hour.
 *
 * Best-effort by contract: callers wrap it in runCatching and it must never change the HTTP outcome of the booking.
 * No Reply-To on purpose: the body carries internal diagnostics, a quoted reply must not reach the customer.
 */
@Service
class BookingFailureAlertService(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val offerRepository: OfferRepository,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    // A customer who hits an error retries (8 times on 19.9.): one mail per customer + offer per window.
    private val lastAlertAt = ConcurrentHashMap<String, Instant>()

    // The per-customer key is request-supplied data, so it cannot be the only limit (fresh e-mail per request, or a
    // partner outage failing everyone at once). Past this many mails an hour the log line is the alert.
    private val sentInLastHour = ArrayDeque<Instant>()

    /** The booking failed after the flow row was committed - everything we need is on the flow. */
    @Transactional(readOnly = true)
    fun alertFailedFlow(
        reservationFlowId: Long,
        cause: Throwable,
        partnerRejection: Boolean,
    ) {
        val flow = reservationFlowRepository.findById(reservationFlowId).orElse(null) ?: return
        send(
            customer = listOfNotNull(flow.name, flow.surname).joinToString(" "),
            email = flow.email,
            phone = flow.phoneNumber,
            yacht = flow.yacht,
            offer = flow.offer,
            price = flow.calculatedTotalPrice,
            reference = "flow $reservationFlowId",
            cause = cause,
            partnerRejection = partnerRejection,
        )
    }

    /** createReservationFlow itself threw, so no flow row exists - the request is all we have. */
    @Transactional(readOnly = true)
    fun alertRejectedRequest(
        dto: CreateReservationDto,
        cause: Throwable,
    ) {
        val offer = offerRepository.findById(dto.offerId).orElse(null)
        send(
            customer = listOfNotNull(dto.name, dto.surname).joinToString(" "),
            email = dto.email,
            phone = dto.phoneNumber,
            yacht = offer?.yacht,
            offer = offer,
            price = null,
            reference = "offer ${dto.offerId}, yacht ${dto.yachtId} (no flow was created)",
            cause = cause,
            partnerRejection = false,
        )
    }

    private fun send(
        customer: String,
        email: String?,
        phone: String?,
        yacht: Yacht?,
        offer: Offer?,
        price: BigDecimal?,
        reference: String,
        cause: Throwable,
        partnerRejection: Boolean,
    ) {
        val now = Instant.now()
        // Blank e-mail or unknown offer must not collapse unrelated customers into one "null|null" key.
        val throttleKey = email?.lowercase()?.takeIf { it.isNotBlank() && offer?.id != null }?.let { "$it|${offer?.id}" }
        val previous = throttleKey?.let { lastAlertAt[it] }
        if (previous != null && Duration.between(previous, now) < THROTTLE) {
            log.info("Booking failure alert ({}) suppressed: same customer + offer alerted at {}", reference, previous)
            return
        }
        synchronized(sentInLastHour) {
            while (sentInLastHour.isNotEmpty() && Duration.between(sentInLastHour.first(), now) >= Duration.ofHours(1)) {
                sentInLastHour.removeFirst()
            }
            if (sentInLastHour.size >= MAX_PER_HOUR) {
                log.error("Booking failure alert ({}) NOT mailed: {} alerts in the last hour already - look at the log", reference, MAX_PER_HOUR)
                return
            }
        }

        val recipients = userRepository.findAllAdminEmailAddresses()
        if (recipients.isEmpty()) {
            log.warn("Booking failure alert ({}): no admin e-mail addresses", reference)
            return
        }

        val customerLabel = customer.ifBlank { "(bez imena)" }
        val yachtLabel =
            listOfNotNull(yacht?.model?.manufacturer?.name, yacht?.model?.name, yacht?.name)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "yacht ${yacht?.id}" }
        val period =
            if (offer?.dateFrom != null && offer.dateTo != null) {
                "${offer.dateFrom!!.format(dateFormatter)} - ${offer.dateTo!!.format(dateFormatter)}"
            } else {
                "(nepoznat termin)"
            }
        // Root cause, not the wrapper (BookingCreationException only says "orchestration failed"). Bounded walk: a
        // cause cycle would otherwise spin here inside a transaction.
        val root = generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).last()

        val body =
            buildString {
                appendLine(
                    if (partnerRejection) {
                        "Kupac je pokušao rezervirati online, ali je PARTNER odbio termin (najčešće: brod više nije slobodan)."
                    } else {
                        "Kupac je pokušao rezervirati online i dobio je grešku NAŠEG sustava."
                    },
                )
                appendLine()
                appendLine("Kupac:    $customerLabel")
                appendLine("E-mail:   ${email?.takeIf { it.isNotBlank() } ?: "-"}")
                appendLine("Telefon:  ${phone?.takeIf { it.isNotBlank() } ?: "-"}")
                appendLine("Brod:     $yachtLabel (yacht ${yacht?.id})")
                appendLine("Termin:   $period (offer ${offer?.id})")
                appendLine("Cijena:   ${price?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "-"}")
                appendLine("Ref:      $reference")
                appendLine()
                appendLine("Uzrok:    ${root::class.simpleName}: ${scrub(root.message)}")
                appendLine()
                if (partnerRejection) {
                    appendLine("Kupcu je prikazana isprika. Vrijedi mu se javiti s alternativom dok je još zainteresiran.")
                } else {
                    appendLine("Kupcu piše da je naš tim obaviješten i da ćemo mu se javiti - javite mu se.")
                    appendLine("Ako je opcija kod partnera već bila kreirana, sustav ju je pokušao otkazati, a ponudu je vratio na slobodno.")
                }
                appendLine("Ponovljeni pokušaji istog kupca za isti termin ne šalju novi mail idućih ${THROTTLE.toMinutes()} min.")
                append("NE odgovarati s Reply (ovaj mail ima interne podatke) - kupcu pisati novim mailom. Log: journalctl -u boat4you")
            }

        val prefix = if (partnerRejection) "ℹ️ Partner odbio rezervaciju" else "🚨 Rezervacija NIJE prošla"
        emailService.sendEmail(
            recipients = recipients,
            subject = "$prefix: $customerLabel, $yachtLabel, $period".replace(LINE_BREAKS, " ").take(MAX_SUBJECT_CHARS),
            templateName = TEMPLATE,
            variables = mapOf("reportBody" to body, "title" to prefix.substringAfter(' ')),
        )
        // Armed only once the mail is queued: a throw above (bad admin address, template) must not silence the
        // customer's next attempts - that would rebuild exactly the incident this class exists for.
        throttleKey?.let {
            if (lastAlertAt.size > MAX_TRACKED) lastAlertAt.values.removeIf { at -> Duration.between(at, now) >= THROTTLE }
            lastAlertAt[it] = now
        }
        synchronized(sentInLastHour) { sentInLastHour.addLast(now) }
        log.info("Booking failure alert ({}) mailed to {} admin(s)", reference, recipients.size)
    }

    /** PostgreSQL appends "Detail: Failing row contains (…)" / "Key (email)=(…)" - other people's data. Keep line one. */
    private fun scrub(message: String?): String =
        message
            ?.lineSequence()
            ?.filterNot { line ->
                val t = line.trimStart()
                t.startsWith("Detail:") || t.startsWith("Where:") || t.startsWith("Hint:") || t.contains("Failing row contains")
            }?.joinToString(" ")
            ?.take(MAX_CAUSE_CHARS)
            ?.ifBlank { "-" }
            ?: "-"

    companion object {
        const val TEMPLATE = "email/adminBookingFailed"
        private val THROTTLE: Duration = Duration.ofMinutes(30)
        private val LINE_BREAKS = Regex("[\\r\\n]+")
        private const val MAX_PER_HOUR = 20
        private const val MAX_TRACKED = 500
        private const val MAX_CAUSE_CHARS = 600
        private const val MAX_CAUSE_DEPTH = 20
        private const val MAX_SUBJECT_CHARS = 140
    }
}
