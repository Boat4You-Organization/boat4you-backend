package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import org.openapitools.client.nausys.model.RestFreeYachtsSearchRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

@Service
class NauSysYachtOfferIntegrationServiceAsync(
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val nauSysYachtOfferSyncService: NauSysYachtOfferSyncService,
    private val transactionTemplate: TransactionTemplate,
    private val nauSysRetryableClient: NauSysRetryableClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Async("taskExecutor")
    fun syncOffersForDateRange(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        countries: List<Long>?,
        regions: List<Long>?,
        marinas: List<Long>?,
    ): CompletableFuture<Unit> {
        // F3-014 (note): "only one Nausys call at a time" was a stale TODO
        // from before scheduler-side serialization existed. The cron-driven
        // catchment paths (`NausysSyncJob.runCatalogueSync` /
        // `runYachtSync` / `runYachtBackupSync` / `availabilitySync`) now
        // each carry @SchedulerLock so VM2 and VM3 cannot fire them at
        // the same time, and the public per-yacht path
        // (`ExternalSyncService.syncYachtOffers(yachtId, ...)`) is
        // serialized cross-VM by YachtSyncMutex's advisory lock.
        // Partner-side global concurrency (a hard "only one outbound
        // request" semaphore) is intentionally NOT enforced here —
        // NauSys has not asked for it, and the partner's own rate-limit
        // is the right place to push back if it ever changes.
        try {
            val freeYachtRequest =
                RestFreeYachtsSearchRequest(
                    credentials = nauSysAuthProvider.auth,
                    periodFrom = NauSysDateWrapper(dateFrom.format(NauSysDateWrapper.DATE_FORMATTER)),
                    periodTo = NauSysDateWrapper(dateTo.format(NauSysDateWrapper.DATE_FORMATTER)),
                    resultsPerPage = 2000,
                    countries = countries,
                    regions = regions,
                    locations = marinas,
                    // Include yachts currently under option so they appear as pre-reserved on the
                    // web instead of being silently filtered out by NauSys. Status (OPTION /
                    // UNDER_OPTION / FREE / ...) is mapped downstream by OfferStatus.fromNausysValue.
                    ignoreOptions = true,
                    // extendedDataSet will be fetch on yacht details opening or offers call
                    // extendedDataSet = "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS",
                )

            val response = nauSysRetryableClient.getFreeYachtsSearchForAsync(freeYachtRequest)
            if (!response.freeYachtsInPeriod.isNullOrEmpty()) {
                transactionTemplate.execute<Unit> {
                    nauSysYachtOfferSyncService.syncOffersForAsync(response.freeYachtsInPeriod!!)
                }
            }
        } catch (e: Exception) {
            log.error("Error while syncing NauSYS yacht offers for date range $dateFrom to $dateTo", e)
        }

        return CompletableFuture.completedFuture(Unit)
    }
}
