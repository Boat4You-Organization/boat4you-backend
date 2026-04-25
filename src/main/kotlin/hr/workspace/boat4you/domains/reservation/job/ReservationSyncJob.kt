package hr.workspace.boat4you.domains.reservation.job

import hr.workspace.boat4you.domains.reservation.service.ReservationSyncService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class ReservationSyncJob(
    private val reservationSyncService: ReservationSyncService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Yacht-swap detection: polls MMK + Nausys for each active reservation,
     * compares partner yachtId with `reservation_flow.yacht_id`. Hourly at :15
     * to avoid overlap with OptionExpiryJob (:00, :30) and the NauSYS
     * availability sync (3:20 / 12:20).
     */
    @Scheduled(cron = "0 15 * * * *")
    fun runYachtSwapSync() {
        log.info("Starting reservation yacht-swap sync")
        val start = System.currentTimeMillis()
        reservationSyncService.syncActiveReservations()
        log.info("Reservation yacht-swap sync took ${System.currentTimeMillis() - start} ms")
    }
}
