package hr.workspace.boat4you.domains.catalouge.job
import hr.workspace.boat4you.domains.catalouge.services.ReservationOfferService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class DeleteExpiredReservationsAndOffersJob(
    private val reservationOfferService: ReservationOfferService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Deletes old reservations for reservation and offer
     * Triggers at 05:30 UTC every day — after the nightly NauSys block (23:00→≈05:15)
     * and before the MMK block starts at 06:00 (de-conflicted timetable 2.9.2026).
     */
    @Scheduled(cron = "0 30 5 * * ?")
    @SchedulerLock(name = "deleteExpiredReservationsAndOffers", lockAtMostFor = "PT1H")
    fun deleteExpiredReservationsAndOffers() {
        log.info("Deleting expired reservations and offers")
        reservationOfferService.deleteExpiredReservationsAndOffers()
    }
}
