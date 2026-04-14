package hr.workspace.boat4you.domains.catalouge.job

import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class ExchangeRateSyncJob(
    private val exchangeRateService: ExchangeRateService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Updates exchange rates with latest ones
     * Triggers at 17:00 PM every day.
     */
    @Scheduled(cron = "0 0 17 * * *", zone = "UTC")
    fun updateExchangeRates() {
        log.info("Updating exchange rates")
        exchangeRateService.updateExchangeRates()
    }
}
