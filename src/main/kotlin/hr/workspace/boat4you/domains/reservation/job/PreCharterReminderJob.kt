package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * "Tomorrow you sail" reminder. Daily cron at 09:32 (offset from
 * top-of-hour clusters and the 12:02/12:12 PaymentPending runs) finds every
 * confirmed reservation whose pickup is tomorrow and dispatches a friendly
 * checklist email — passport / sailing licence / what to pack / agency
 * contact. No financial detail by design (Mario rule, May 2026).
 */
@Profile("data-sync")
@Component
class PreCharterReminderJob(
    private val reservationRepository: ReservationRepository,
    private val reservationEmailService: ReservationEmailService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 32 9 ? * *")
    @SchedulerLock(name = "preCharterReminder", lockAtMostFor = "PT45M")
    @Transactional(readOnly = true)
    fun run() {
        val tomorrow = LocalDate.now().plusDays(1)
        val start = tomorrow.atStartOfDay()
        val end = tomorrow.plusDays(1).atStartOfDay()
        val reservations = reservationRepository.findConfirmedStartingBetween(
            status = ReservationStatus.RESERVATION,
            startTime = start,
            endTime = end,
        )
        log.info("PreCharterReminderJob: found ${reservations.size} reservation(s) for $tomorrow")
        reservations.forEach { reservation ->
            val id = reservation.id ?: return@forEach
            runCatching { reservationEmailService.sendPreCharterReminder(id) }
                .onFailure { log.error("Failed pre-charter reminder for reservation $id", it) }
        }
    }
}
