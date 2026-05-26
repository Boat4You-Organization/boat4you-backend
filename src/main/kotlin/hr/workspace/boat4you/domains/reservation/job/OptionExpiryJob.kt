package hr.workspace.boat4you.domains.reservation.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
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
    @SchedulerLock(name = "optionExpirySend24h", lockAtMostFor = "PT30M")
    fun send24HourOptionExpirationReminder() {
        log.info("Sending 24 hours option expiry reminder email")
        optionExpiryJobService.send24HourOptionExpirationReminder()
    }

    /**
     * Sends "midway" reminder 72 hours before option expires for LONG
     * options (≥ 4 days total window). Mario decision 1.5.2026: long
     * options need a midway nudge so the customer doesn't go silent for
     * 4+ days between creation and the 48h reminder.
     */
    @Scheduled(cron = "0 25 * * * *")
    @SchedulerLock(name = "optionExpirySend72h", lockAtMostFor = "PT30M")
    fun send72HourOptionExpirationReminder() {
        log.info("Sending 72 hours midway option expiry reminder email")
        optionExpiryJobService.send72HourOptionExpirationReminder()
    }

    /**
     * Sends reminder 48 hours before reservation option expires
     * Triggers every hour of the day.
     */
    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "optionExpirySend48h", lockAtMostFor = "PT30M")
    fun send48HourOptionExpirationReminder() {
        log.info("Sending 48 hours option expiry reminder email")
        optionExpiryJobService.send48HourOptionExpirationReminder()
    }

    /**
     * This job runs every 30 minutes and checks for reservations in OPTION status expired.
     * If the external reservation status has changed, it updates the local reservation status
     * and sends `optionExpired.html` email to the customer (effectively the "your option was
     * cancelled because we didn't receive payment" notice). The 24h reminder above is the
     * customer's last warning — Mario decision 1.5.2026: no further reminders below 24h, the
     * cancellation is the next step.
     */
    @Scheduled(cron = "0 */30 * * * ?")
    @SchedulerLock(name = "optionExpirySync", lockAtMostFor = "PT20M")
    fun syncExpiredOptions() {
        optionExpiryJobService.syncExpiredOptions()
    }
}
