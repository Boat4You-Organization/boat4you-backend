package hr.workspace.boat4you.domains.reservation.job

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class OptionExpiryJob(
    private val optionExpiryJobService: OptionExpiryService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Sends reminder 24 hours before reservation option expires
     * Triggers every hour of the day.
     */
    @Scheduled(cron = "0 0 * * * *")
    fun send24HourOptionExpirationReminder() {
        log.info("Sending 24 hours option expiry reminder email")
        optionExpiryJobService.send24HourOptionExpirationReminder()
    }

    /**
     * Sends reminder 48 hours before reservation option expires
     * Triggers every hour of the day.
     */
    @Scheduled(cron = "0 5 * * * *")
    fun send48HourOptionExpirationReminder() {
        log.info("Sending 48 hours option expiry reminder email")
        optionExpiryJobService.send48HourOptionExpirationReminder()
    }

    /**
     * This job runs every 30 minutes and checks for reservations in OPTION status expired
     * If the external reservation status has changed, it updates the local reservation status.
     */
    @Scheduled(cron = "0 */30 * * * ?")
    fun syncExpiredOptions() {
        optionExpiryJobService.syncExpiredOptions()
    }
}
