package hr.workspace.boat4you.domains.reservation.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class PaymentPendingNotificationJob(
    private val paymentPendingNotificationService: PaymentPendingNotificationService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Runs at 12:02 (shifted from 12:00 to stagger against other top-of-hour
     * jobs like OptionExpiry :00/:05 and ExchangeRate + Delete jobs that cluster
     * on the hour). 1-day-ahead payment reminders.
     */
    @Scheduled(cron = "0 2 12 ? * *")
    @SchedulerLock(name = "paymentPendingNotification1Day", lockAtMostFor = "PT30M")
    fun run1DayInAdvance() {
        log.info("Running PaymentPendingNotificationJob for one day in advance")
        paymentPendingNotificationService.sendPaymentReminder(1)
    }

    /**
     * Runs at 12:12 (shifted from 12:10 so it doesn't immediately follow the
     * 12:02 run while the DB is still finishing that batch). 3-day-ahead
     * reminders. Stays clear of NausysSyncJob 12:20 follow-up.
     */
    @Scheduled(cron = "0 12 12 ? * *")
    @SchedulerLock(name = "paymentPendingNotification3Days", lockAtMostFor = "PT30M")
    fun run3DaysInAdvance() {
        log.info("Running PaymentPendingNotificationJob for three days in advance")
        paymentPendingNotificationService.sendPaymentReminder(3)
    }
}
