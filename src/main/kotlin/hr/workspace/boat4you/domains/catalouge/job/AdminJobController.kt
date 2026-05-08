package hr.workspace.boat4you.domains.catalouge.job

import hr.workspace.boat4you.domains.reservation.job.OptionExpiryJob
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Manual triggers for the cron-driven jobs running on VM3. The cron entries
// in NausysSyncJob / OptionExpiryJob / etc. are the source of truth; this
// controller just lets a SYSTEM_ADMIN run a job on demand (config tweak,
// missed schedule, ad-hoc reconciliation). Renamed from `TestJobController`
// (the Test prefix made it look like throwaway / dev-only code that could
// be removed before prod). Class is profile-gated to `data-sync`, so it's
// only registered on the scheduler VM (F1-044).
@RestController
@RequestMapping("/admin/job")
@Profile("data-sync")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminJobController(
    private val deleteExpiredReservationsAndOffersJob: DeleteExpiredReservationsAndOffersJob,
    private val exchangeRateSyncJob: ExchangeRateSyncJob,
    private val optionExpiryJob: OptionExpiryJob,
) {
    @PostMapping("/deleteExpiredReservationsAndOffers")
    fun deleteExpiredReservationsAndOffers() {
        deleteExpiredReservationsAndOffersJob.deleteExpiredReservationsAndOffers()
    }

    @PostMapping("/updateExchangeRates")
    fun updateExchangeRates() {
        exchangeRateSyncJob.updateExchangeRates()
    }

    @PostMapping("/send24HourOptionExpirationReminder")
    fun send24HourOptionExpirationReminder() {
        optionExpiryJob.send24HourOptionExpirationReminder()
    }

    @PostMapping("/send48HourOptionExpirationReminder")
    fun send48HourOptionExpirationReminder() {
        optionExpiryJob.send48HourOptionExpirationReminder()
    }
}
