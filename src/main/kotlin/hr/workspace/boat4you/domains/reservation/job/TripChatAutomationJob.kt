package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripChatMessageRepository
import hr.workspace.boat4you.domains.reservation.service.TripChatService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Concierge automations (locked 4.7.2026: "predlošci + zakazane objave") +
 * the admin's daily chat digest. Scheduled posts are idempotent via
 * automation_tag; the T-1 post does NOT push (TripPushJob's own T-1 push
 * covers that minute-window) while the T-14 itinerary post does. Digest =
 * yesterday's guest/owner messages, English, to the admin users — replaces
 * per-message notifications on Mario's phone by explicit decision.
 */
@Profile("data-sync")
@Component
class TripChatAutomationJob(
    private val reservationRepository: ReservationRepository,
    private val tripChatService: TripChatService,
    private val chatMessageRepository: TripChatMessageRepository,
    private val emailService: EmailService,
    private val userRepository: UserRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 45 9 ? * *")
    @SchedulerLock(name = "tripChatAutomation", lockAtMostFor = "PT45M")
    @Transactional
    fun run() {
        val today = LocalDate.now()
        var posted = 0

        startingOn(today.plusDays(ITINERARY_DAYS_AHEAD)).forEach { reservation ->
            val id = reservation.id ?: return@forEach
            runCatching {
                tripChatService.postAsConcierge(id, itineraryPost(reservation), automationTag = "itinerary_t14")
                    ?.also { posted++ }
            }.onFailure { log.error("Trip itinerary post failed for reservation $id", it) }
        }
        startingOn(today.plusDays(1)).forEach { reservation ->
            val id = reservation.id ?: return@forEach
            runCatching {
                tripChatService.postAsConcierge(id, readyPost(reservation), automationTag = "ready_t1", push = false)
                    ?.also { posted++ }
            }.onFailure { log.error("Trip ready post failed for reservation $id", it) }
        }

        runCatching { sendDigest() }.onFailure { log.error("Trip chat digest failed", it) }
        log.info("TripChatAutomationJob: posted $posted concierge message(s)")
    }

    private fun startingOn(date: LocalDate): List<Reservation> = reservationRepository.findConfirmedStartingBetween(
        status = ReservationStatus.RESERVATION,
        startTime = date.atStartOfDay(),
        endTime = date.plusDays(1).atStartOfDay(),
    )

    private fun itineraryPost(reservation: Reservation): String {
        val yacht = reservation.reservationFlow?.yacht?.name?.takeIf { it.isNotBlank() } ?: "your yacht"
        val marina = reservation.locationFrom?.name?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
        val date = reservation.dateFrom?.toLocalDate()?.format(DATE_FORMAT) ?: "soon"
        return "Hello crew! 👋 $yacht sets sail on $date$marina — two weeks to go. " +
            "Check the travel documents at the top of this hub, coordinate arrivals right here in the chat, " +
            "and if you'd like anything extra for the week (transfers, provisioning, special wishes), " +
            "just reply — your Boat4You concierge reads every message."
    }

    private fun readyPost(reservation: Reservation): String {
        val yacht = reservation.reservationFlow?.yacht?.name?.takeIf { it.isNotBlank() } ?: "your yacht"
        val marina = reservation.locationFrom?.name?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
        return "Tomorrow's the day! ⛵ Check-in for $yacht$marina. A passport for every guest and the skipper's " +
            "licence are the two things the base will ask for — everything else is sunshine. " +
            "Need anything last-minute? Reply here. Fair winds!"
    }

    /** Yesterday's human traffic → one internal email; silent when empty. */
    private fun sendDigest() {
        val since = Instant.now().minus(1, ChronoUnit.DAYS)
        val messages = chatMessageRepository.findForDigest(since)
        if (messages.isEmpty()) return
        val reservations = reservationRepository.findAllById(messages.mapNotNull { it.reservationId }.toSet())
            .associateBy { it.id }
        val trips = messages.groupBy { it.reservationId }.map { (reservationId, list) ->
            val reservation = reservations[reservationId]
            mapOf(
                "reservationNumber" to (reservation?.reservationNumber ?: "#$reservationId"),
                "yachtName" to (reservation?.reservationFlow?.yacht?.name ?: ""),
                "messageCount" to list.size,
                "lines" to list.map { message ->
                    val time = message.createdAt.atZone(ZoneId.of("UTC")).format(TIME_FORMAT)
                    "$time · ${message.senderName}: ${message.body?.take(DIGEST_PREVIEW_LENGTH)}"
                },
            )
        }
        val adminEmails = userRepository.findAllAdminEmailAddresses()
        if (adminEmails.isEmpty()) return
        emailService.sendEmail(
            recipients = adminEmails,
            subject = "Trip chat digest — ${messages.size} message(s) across ${trips.size} trip(s)",
            templateName = "email/tripChatDigest",
            variables = mapOf("trips" to trips),
        )
    }

    companion object {
        private const val ITINERARY_DAYS_AHEAD = 14L
        private const val DIGEST_PREVIEW_LENGTH = 300
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
