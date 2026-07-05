package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.TripChatService
import hr.workspace.boat4you.domains.reservation.service.TripPhotoService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Boat4You Trip concierge automations (locked 4.7.2026: "predlošci + zakazane
 * objave"). Scheduled posts are idempotent via automation_tag. The T-1 post
 * does NOT push (TripPushJob's own T-1 push covers that minute-window); the
 * T-14 itinerary and the T+1 album-ready posts do. After the charter, if the
 * crew uploaded photos, the "album ready" post tells them to open their hub
 * and download the lot — that IS the delivery of their download link (guests
 * have no e-mail; the broker keeps a GDPR-minimal download in admin instead of
 * any chat access).
 */
@Profile("data-sync")
@Component
class TripChatAutomationJob(
    private val reservationRepository: ReservationRepository,
    private val tripChatService: TripChatService,
    private val tripPhotoService: TripPhotoService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // Deliberately NOT @Transactional: each postAsConcierge runs its own tx,
    // so one failing reservation cannot poison the whole batch (review 5.7:
    // a joined outer tx turned per-post runCatching into all-or-nothing) and
    // no connection is pinned across web-push calls. The fetch queries
    // JOIN FETCH everything the message builders touch.
    @Scheduled(cron = "0 45 9 ? * *")
    @SchedulerLock(name = "tripChatAutomation", lockAtMostFor = "PT45M")
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
        endedOn(today.minusDays(1)).forEach { reservation ->
            val id = reservation.id ?: return@forEach
            if (tripPhotoService.count(id) <= 0) return@forEach
            runCatching {
                tripChatService.postAsConcierge(id, albumReadyPost(reservation), automationTag = "album_ready_t1")
                    ?.also { posted++ }
            }.onFailure { log.error("Trip album-ready post failed for reservation $id", it) }
        }

        log.info("TripChatAutomationJob: posted $posted concierge message(s)")
    }

    private fun startingOn(date: LocalDate): List<Reservation> =
        reservationRepository.findConfirmedStartingBetweenWithMarina(
            status = ReservationStatus.RESERVATION,
            startTime = date.atStartOfDay(),
            endTime = date.plusDays(1).atStartOfDay(),
        )

    private fun endedOn(date: LocalDate): List<Reservation> = reservationRepository.findConfirmedEndingBetween(
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

    private fun albumReadyPost(reservation: Reservation): String {
        val yacht = reservation.reservationFlow?.yacht?.name?.takeIf { it.isNotBlank() } ?: "your yacht"
        val deadline = reservation.dateTo?.toLocalDate()?.plusDays(PHOTO_RETENTION_DAYS)?.format(DATE_FORMAT)
        val deadlineNote = deadline?.let {
            " Please download them by $it — after that we permanently remove all trip photos from our system (GDPR)."
        } ?: ""
        return "📸 Your $yacht photos are ready! Open the Chat tab of this trip, scroll to the album and tap " +
            "“Download all photos” to keep the whole week.$deadlineNote Thank you for sailing with Boat4You 💙"
    }

    companion object {
        private const val ITINERARY_DAYS_AHEAD = 14L
        private const val PHOTO_RETENTION_DAYS = 10L
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")
    }
}
