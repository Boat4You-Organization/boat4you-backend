package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import org.openapitools.client.nausys.model.RestFreeYachtsSearchRequest
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Service
class NauSysYachtOfferIntegrationServiceAsync(
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val nauSysYachtOfferSyncService: NauSysYachtOfferSyncService,
    private val nauSysRetryableClient: NauSysRetryableClient,
    private val searchRetryQueue: NausysSearchSyncRetryQueue,
) {
    companion object {
        /**
         * Wall-clock budget of one search-retry drain. Kept under the PT10M ShedLock and the
         * 15-min gate wait of the nightly run so a slow NauSys can never make the drain
         * outlive the slot; rows not reached simply wait for the next 15-min run.
         */
        val DRAIN_BUDGET: Duration = Duration.ofMinutes(5)
    }

    /** One drain run of `nausys_search_sync_retry`; `notAttempted` = due rows left for the next slot (budget or 429 stop). */
    data class SearchRetryDrainSummary(
        val due: Int = 0,
        val ok: Int = 0,
        val failed: Int = 0,
        val gaveUp: Int = 0,
        val notAttempted: Int = 0,
    )

    // Runs on the CALLER's thread by design (no @Async): the callers are
    // ExternalSyncService.syncYachtOffers (itself already a taskExecutor task) and
    // drainSearchRetryQueue below (scheduler thread). Re-dispatching to the same
    // bounded pool deadlock-starved the inner task behind outer ones and burned the
    // outer thread's 5-minute wait.
    //
    // Failures PROPAGATE (until 5.9.2026 they were swallowed and logged here): the
    // callers classify them — ExternalSyncService parks transient NauSys failures in
    // nausys_search_sync_retry and still writes the 3 h marker, the drain bumps or
    // deletes the row. Throws NauSysRateLimitedException (429 budget / gate),
    // HttpServerErrorException (5xx), ResourceAccessException (timeouts), other
    // RestClientExceptions and DB exceptions from syncOffersForAsync.
    fun syncOffersForDateRangeBlocking(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        countries: List<Long>?,
        regions: List<Long>?,
        marinas: List<Long>?,
    ) {
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
            // No outer transaction on purpose: syncOffersForAsync opens its own
            // short per-yacht transactions (advisory-locked) — a suspended outer
            // tx would just pin a second Hikari connection for the whole batch.
            nauSysYachtOfferSyncService.syncOffersForAsync(response.freeYachtsInPeriod!!)
        }
    }

    /**
     * Replays due rows of `nausys_search_sync_retry` (failed non-weekly search warms
     * parked by the API node — Mario, 5.9.2026: the scheduler, not cusma2, retries
     * NauSys). Oldest `next_attempt_at` first, at most [maxRows]; success deletes the
     * row, failure backs off 15 min × attempts and gives up after 6. Stops early on the
     * first [NauSysRateLimitedException] (NauSys is saturated — burning an attempt on
     * every remaining row would only deepen the 429 storm) and when [budget] is spent.
     * No per-row logging here; the caller logs one summary line.
     */
    fun drainSearchRetryQueue(
        maxRows: Int = NausysSearchSyncRetryQueue.MAX_ROWS_PER_DRAIN,
        budget: Duration = DRAIN_BUDGET,
    ): SearchRetryDrainSummary {
        val rows = searchRetryQueue.due(maxRows)
        if (rows.isEmpty()) {
            return SearchRetryDrainSummary()
        }
        val deadline = Instant.now().plus(budget)
        var ok = 0
        var failed = 0
        var gaveUp = 0
        var attempted = 0
        for (row in rows) {
            if (Instant.now().isAfter(deadline)) {
                break
            }
            attempted++
            try {
                syncOffersForDateRangeBlocking(row.periodFrom!!, row.periodTo!!, row.countryIds(), row.regionIds(), row.locationIds())
                searchRetryQueue.markSuccess(row)
                ok++
            } catch (e: NauSysRateLimitedException) {
                // NauSys is saturated: mark this row and leave the rest for the next slot.
                if (searchRetryQueue.markFailure(row, e)) gaveUp++ else failed++
                break
            } catch (e: Exception) {
                if (searchRetryQueue.markFailure(row, e)) gaveUp++ else failed++
            }
        }
        return SearchRetryDrainSummary(due = rows.size, ok = ok, failed = failed, gaveUp = gaveUp, notAttempted = rows.size - attempted)
    }
}
