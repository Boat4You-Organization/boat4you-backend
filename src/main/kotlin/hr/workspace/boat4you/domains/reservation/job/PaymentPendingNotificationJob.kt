package hr.workspace.boat4you.domains.reservation.job

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
     * This job runs every day at 12:00 pm, checks for upcoming booking payments and notifies users 1 day in advance
     */
    @Scheduled(cron = "0 0 12 ? * *")
    fun run1DayInAdvance() {
        log.info("Running PaymentPendingNotificationJob for one day in advance")
        paymentPendingNotificationService.sendPaymentReminder(1)
    }

    /**
     * This job runs every day at 12:10 pm, checks for upcoming booking payments and notifies users 3 days in advance
     */
    @Scheduled(cron = "0 10 12 ? * *")
    fun run3DaysInAdvance() {
        log.info("Running PaymentPendingNotificationJob for three days in advance")
        paymentPendingNotificationService.sendPaymentReminder(3)
    }
}
