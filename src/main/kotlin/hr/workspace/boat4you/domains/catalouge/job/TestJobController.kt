package hr.workspace.boat4you.domains.catalouge.job

import hr.workspace.boat4you.domains.reservation.job.OptionExpiryJob
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/job")
@Profile("data-sync")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class TestJobController(
    private val deleteExpiredReservationsAndOffersJob: DeleteExpiredReservationsAndOffersJob,
    private val exchangeRateSyncJob: ExchangeRateSyncJob,
    private val optionExpiryJob: OptionExpiryJob,
) {
    @DeleteMapping("/deleteExpiredReservationsAndOffers")
    fun deleteExpiredReservationsAndOffers() {
        deleteExpiredReservationsAndOffersJob.deleteExpiredReservationsAndOffers()
    }

    @PostMapping("/updateExchangeRates")
    fun updateExchangeRates() {
        exchangeRateSyncJob.updateExchangeRates()
    }

    @GetMapping("/send24HourOptionExpirationReminder")
    fun send24HourOptionExpirationReminder() {
        optionExpiryJob.send24HourOptionExpirationReminder()
    }

    @GetMapping("/send48HourOptionExpirationReminder")
    fun send48HourOptionExpirationReminder() {
        optionExpiryJob.send48HourOptionExpirationReminder()
    }

//    @GetMapping("/sendOptionExpiredNotification")
//    fun sendOptionExpiredNotification() {
//        optionExpiryJob.sendOptionExpiredNotification()
//    }
}
