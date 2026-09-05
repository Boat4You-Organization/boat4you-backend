package hr.workspace.boat4you.domains.external.nausys.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.nausys.config.NauSysRateLimitStats
import hr.workspace.boat4you.domains.external.nausys.service.NauSysAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NauSys scheduler timetable (UTC, de-conflicted 2.9.2026 — the single
 * rest@EURCU credential is throttled on CONCURRENT calls, and the ~37k-call
 * offer sync measured 5.0–5.7 h, so nothing else may talk to NauSys while it
 * runs):
 *
 *  23:00 catalogue → 23:20 yachts + offers → (chained) availability pass →
 *  (chained) offer retry-queue drain, expected to finish ≈04:45–05:15.
 *  10:20 / 16:20 / 22:20 availability passes; 06:15 / 10:15 / 15:15 backup
 *  slots (no-op unless a marker is >24 h old; they always drain the retry
 *  queues). Note: the "06:15 backup" is this NauSys BACKUP-SYNC, not a DB backup.
 *  Every 15 min (:05 :20 :35 :50) the search retry drain replays failed
 *  on-demand search warms parked by the API node (Mario, 5.9.2026: search is
 *  served from the DB, only the scheduler retries NauSys); it skips while
 *  another NauSys job holds the gate.
 *
 * [nausysBusy] is the in-JVM sequencing gate: ShedLock only stops two nodes
 * from running the SAME job, not two different NauSys jobs on this node.
 */
@Profile("data-sync")
@Component
class NausysSyncJob(
    private val nauSysYachtOfferIntegrationService: NauSysYachtOfferIntegrationService,
    private val nauSysYachtIntegrationService: NauSysYachtIntegrationService,
    private val nauSysCatalogueIntegrationService: NauSysCatalogueIntegrationService,
    private val nauSysAvailabilityIntegrationService: NauSysAvailabilityIntegrationService,
    private val serviceCallCacheService: ServiceCallCacheService,
    private val rateLimitStats: NauSysRateLimitStats,
    private val nauSysYachtOfferIntegrationServiceAsync: NauSysYachtOfferIntegrationServiceAsync,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /** True while any NauSys sync (yachts/offers/backup/availability/search-retry drain) is running in this JVM. */
    internal val nausysBusy = AtomicBoolean(false)

    // How long availabilitySync waits for a running offer/backup sync (inside its PT1H lock).
    internal var availabilityWaitPollMs: Long = 30_000
    internal var availabilityWaitMaxMs: Long = 45 * 60_000
    internal var sleep: (Long) -> Unit = Thread::sleep

    // How long the nightly (23:20) and backup (:15) runs wait for the gate. They used to
    // skip outright; since 5.9.2026 the 15-min search retry drain fires at :20 / :05 too
    // and may legitimately hold the gate for seconds (or a few minutes) at 23:20:00 —
    // a skip there would cancel the whole night, so they wait like availabilitySync.
    internal var gateWaitMaxMs: Long = 15 * 60_000

    companion object {
        /** A STARTED marker younger than this with no newer FINISHED marker = a night still running. */
        val STARTED_MARKER_FRESHNESS: Duration = Duration.ofHours(8)
    }

    /**
     * Syncs all NauSYS catalogue data, including agencies, countries, regions, locations, vessel types, manufacturers, models.
     * 23:00 UTC — opens the nightly NauSys block (was 01:00).
     */
    @Scheduled(cron = "0 0 23 * * ?")
    @SchedulerLock(name = "nausysCatalogueSync", lockAtMostFor = "PT2H")
    fun runCatalogueSync() {
        nauSysCatalogueIntegrationService.countriesSync()
        nauSysCatalogueIntegrationService.regionsSync()
        nauSysCatalogueIntegrationService.locationsSync()
        nauSysCatalogueIntegrationService.agenciesSync()
        nauSysCatalogueIntegrationService.categoriesSync()
        nauSysCatalogueIntegrationService.manufacturerSync()
        nauSysCatalogueIntegrationService.modelsSync()
        nauSysCatalogueIntegrationService.equipmentSync()
        nauSysCatalogueIntegrationService.syncServices()
        nauSysCatalogueIntegrationService.seasonsSync()
        nauSysCatalogueIntegrationService.basesSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_CATALOGUE_SYNC)
    }

    fun eliminateDuplicateModels() {
        log.info("NauSYS eliminateDuplicateModels")
        nauSysCatalogueIntegrationService.eliminateDuplicateModels()
        log.info("Finished NauSYS eliminateDuplicateModels")
    }

    /**
     * Backup sync in case main sync fails.
     */
    @Scheduled(cron = "0 0 6,10,15 * * ?")
    @SchedulerLock(name = "nausysCatalogueBackupSync", lockAtMostFor = "PT1H")
    fun runCatalogueBackupSync() {
        if (!serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_CATALOGUE_SYNC)) {
            return
        }
        if (!nausysBusy.compareAndSet(false, true)) {
            log.info("NauSYS catalogue backup sync skipped: another NauSys sync is running")
            return
        }
        try {
            nauSysCatalogueIntegrationService.countriesSync()
            nauSysCatalogueIntegrationService.regionsSync()
            nauSysCatalogueIntegrationService.locationsSync()
            nauSysCatalogueIntegrationService.agenciesSync()
            nauSysCatalogueIntegrationService.categoriesSync()
            nauSysCatalogueIntegrationService.manufacturerSync()
            nauSysCatalogueIntegrationService.modelsSync()
            nauSysCatalogueIntegrationService.equipmentSync()
            nauSysCatalogueIntegrationService.syncServices()
            nauSysCatalogueIntegrationService.seasonsSync()
            nauSysCatalogueIntegrationService.basesSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_CATALOGUE_SYNC)
        } finally {
            nausysBusy.set(false)
        }
    }

    /**
     * Syncs all NauSYS yachts data, then the full offer grid, then (chained, so it can
     * never overlap the offer sync) the first daily availability pass and the offer
     * retry-queue drain. 23:20 UTC (was 01:30).
     */
    @Scheduled(cron = "0 20 23 * * ?")
    // PT7H: measured full runs are 5.0–5.7 h (yachts 11–15 min + offers 300–345 min).
    // The old PT4H released the lock at 05:30 while the job was still running —
    // ShedLock does not stop the thread, it just frees the name.
    @SchedulerLock(name = "nausysYachtSync", lockAtMostFor = "PT7H")
    fun runYachtSync() {
        if (!acquireGate("yacht sync", gateWaitMaxMs)) {
            return
        }
        val before = rateLimitStats.snapshot()
        try {
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED)
            log.info("Syncing NauSYS yachts")
            val startTimeYachts = System.currentTimeMillis()
            nauSysYachtIntegrationService.yachtSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)
            log.info("Syncing NauSYS yachts took ${System.currentTimeMillis() - startTimeYachts} ms")

            log.info("Syncing NauSYS offers")
            val startTimeOffer = System.currentTimeMillis()
            nauSysYachtOfferIntegrationService.yachtOfferSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
            log.info("Syncing NauSYS yacht offers took ${System.currentTimeMillis() - startTimeOffer} ms")

            // First availability pass of the day, chained instead of a fixed cron so it is
            // sequential with the offer sync by construction (replaces the 04:20 slot that
            // overlapped the running offer sync every night).
            runAvailabilityPass("chained")
            runRetryDrain("chained")
        } finally {
            nausysBusy.set(false)
            logRateLimitSummary("nightly yacht+offer sync", before)
        }
    }

    /**
     * Backup sync in case main sync fails. Always drains the offer retry queue first
     * (rows deferred by 429/5xx/parse failures), then re-runs yachts/offers only when
     * their finish marker is >24 h old AND no nightly run is still in flight.
     */
    @Scheduled(cron = "0 15 6,10,15 * * ?")
    @SchedulerLock(name = "nausysYachtBackupSync", lockAtMostFor = "PT2H")
    fun runYachtBackupSync() {
        if (!acquireGate("yacht backup sync", gateWaitMaxMs)) {
            return
        }
        val before = rateLimitStats.snapshot()
        try {
            runRetryDrain("backup slot")
            if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)) {
                log.info("Syncing NauSYS yachts")
                val startTime = System.currentTimeMillis()
                nauSysYachtIntegrationService.yachtSync()
                serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)
                log.info("Syncing NauSYS yachts took ${System.currentTimeMillis() - startTime} ms")
            }
            if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)) {
                if (nightlyOfferSyncStillRunning()) {
                    log.info("NauSYS offer backup sync skipped: the nightly offer sync started <8h ago and has not finished (other JVM / restart)")
                    return
                }
                log.info("Syncing NauSYS offers")
                val startTime = System.currentTimeMillis()
                nauSysYachtOfferIntegrationService.yachtOfferSync()
                serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
                log.info("Syncing NauSYS yacht offers took ${System.currentTimeMillis() - startTime} ms")
            }
        } finally {
            nausysBusy.set(false)
            logRateLimitSummary("backup slot", before)
        }
    }

    /** STARTED marker newer than the FINISHED one and younger than 8 h → a night is (or was, until a crash) in flight. */
    internal fun nightlyOfferSyncStillRunning(): Boolean {
        val startedAt = serviceCallCacheService.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER_STARTED) ?: return false
        val finishedAt = serviceCallCacheService.lastScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
        val notFinished = finishedAt == null || startedAt.isAfter(finishedAt)
        return notFinished && startedAt.isAfter(Instant.now().minus(STARTED_MARKER_FRESHNESS))
    }

    /**
     * DISABLED 2026-06-17 (Mario): offers/prices now sync ONCE per day (the nightly
     * runYachtSync pass), matching NauSys's documented recommendation — API v6
     * Implementation Guidelines / "Full implementation": "Synchronisation of the
     * prices: once per day". Intraday FRESHNESS now comes from the
     * occupancy/availability sync, bumped to 4x/day (availabilitySync below), per
     * NauSys's "make synchronisation of occupancy more often, every few hours".
     * Rationale: PRICES are stable enough for a daily copy; AVAILABILITY (the
     * booking-critical signal, surfaced via external_reservations) is what needs to
     * be frequent. This also removes the single heaviest scheduled NauSys call
     * source — the full interval-grid getFreeYachts re-pull ran 4x/day.
     *
     * This reverses the 13.6 intraday rule ("sync kao MMK"). To RESTORE intraday
     * price re-pricing (if daily prices prove too stale for some agency), just
     * un-comment the two annotations below.
     * Was: @Scheduled(cron = "0 0 8,13,18 * * ?")
     */
    // @Scheduled(cron = "0 0 8,13,18 * * ?")
    // @SchedulerLock(name = "nausysOfferIntradaySync", lockAtMostFor = "PT2H")
    fun runIntradayOfferSync() {
        log.info("Starting NauSYS intraday offer sync")
        val startTime = System.currentTimeMillis()
        nauSysYachtOfferIntegrationService.yachtOfferSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
        log.info("NauSYS intraday offer sync took ${System.currentTimeMillis() - startTime} ms")
    }

    /**
     * Syncs NauSYS availability/occupancy for all yachts from NauSYS agencies.
     * Still 4 passes/day: the first is CHAINED at the end of the nightly offer sync
     * (≈04:45–05:15 UTC; was a fixed 04:20 slot that ran in parallel with the offer
     * sync every night), the other three run at 10:20 / 16:20 / 22:20. This is the
     * PRIMARY freshness engine for the site (the intraday OFFER/price re-sync was
     * retired 2026-06-17; per NauSys docs prices sync 1x/day, occupancy "more often,
     * every few hours"). If another NauSys sync is still running the pass waits
     * (poll 30 s, max 45 min, inside the PT1H lock) and otherwise skips to the next slot.
     */
    @Scheduled(cron = "0 20 10,16,22 * * *")
    @SchedulerLock(name = "nausysAvailabilitySync", lockAtMostFor = "PT1H")
    fun availabilitySync() {
        if (!acquireGate("availability sync", availabilityWaitMaxMs)) {
            return
        }
        val before = rateLimitStats.snapshot()
        try {
            runAvailabilityPass("scheduled")
        } finally {
            nausysBusy.set(false)
            logRateLimitSummary("availability sync", before)
        }
    }

    fun runOfferSync() {
        log.info("Syncing NauSYS offers")
        val startTimeOffer = System.currentTimeMillis()
        nauSysYachtOfferIntegrationService.yachtOfferSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
        log.info("Syncing NauSYS yacht offers took ${System.currentTimeMillis() - startTimeOffer} ms")
    }

    private fun runAvailabilityPass(trigger: String) {
        log.info("Starting NauSYS availability sync ($trigger)")
        val startTime = System.currentTimeMillis()
        try {
            nauSysAvailabilityIntegrationService.syncYachtAvailability()
        } catch (e: Exception) {
            log.error("NauSYS availability sync ($trigger) failed", e)
        }
        log.info("Syncing NauSYS availability took ${System.currentTimeMillis() - startTime} ms")
    }

    /** Agency queue (nausys_offer_sync_retry) first, then the search queue (nausys_search_sync_retry); gate already held by the caller. */
    private fun runRetryDrain(trigger: String) {
        val startTime = System.currentTimeMillis()
        try {
            nauSysYachtOfferIntegrationService.drainRetryQueue()
        } catch (e: Exception) {
            log.error("NauSYS offer retry drain ($trigger) failed", e)
        }
        log.info("NauSYS offer retry drain ($trigger) took ${System.currentTimeMillis() - startTime} ms")
        val searchStart = System.currentTimeMillis()
        try {
            val summary = nauSysYachtOfferIntegrationServiceAsync.drainSearchRetryQueue()
            log.info("NauSYS search retry drain ($trigger): ${describe(summary)} took ${System.currentTimeMillis() - searchStart} ms")
        } catch (e: Exception) {
            log.error("NauSYS search retry drain ($trigger) failed", e)
        }
    }

    /**
     * Replays failed on-demand search warms parked by the API node in
     * `nausys_search_sync_retry` (Mario, 5.9.2026: search is served from the DB, the
     * API node never retries NauSys itself — only this scheduler does). Every 15 min at
     * :05 :20 :35 :50, ≤25 rows oldest `next_attempt_at` first, 15 min × attempts
     * back-off, give up after 6 (WARN). Skips (no wait) while another NauSys job holds
     * the gate, so during the nightly block it is a no-op; one INFO summary line only
     * when something was due.
     */
    @Scheduled(cron = "0 5,20,35,50 * * * *")
    @SchedulerLock(name = "nausysSearchRetryDrain", lockAtMostFor = "PT10M")
    fun runSearchRetryDrain() {
        if (!nausysBusy.compareAndSet(false, true)) {
            log.info("NauSYS search retry drain skipped: another NauSys sync is running")
            return
        }
        val before = rateLimitStats.snapshot()
        val startTime = System.currentTimeMillis()
        try {
            val summary = nauSysYachtOfferIntegrationServiceAsync.drainSearchRetryQueue()
            if (summary.due > 0) {
                val delta = rateLimitStats.snapshot().since(before)
                log.info(
                    "NauSYS search retry drain (scheduled): ${describe(summary)} took ${System.currentTimeMillis() - startTime} ms, " +
                        "429s this run = ${delta.tooManyRequests}",
                )
            }
        } catch (e: Exception) {
            log.error("NauSYS search retry drain (scheduled) failed", e)
        } finally {
            nausysBusy.set(false)
        }
    }

    private fun describe(summary: NauSysYachtOfferIntegrationServiceAsync.SearchRetryDrainSummary): String =
        "due=${summary.due} ok=${summary.ok} failed=${summary.failed} gaveUp=${summary.gaveUp} notAttempted=${summary.notAttempted}"

    /**
     * Takes [nausysBusy], polling every [availabilityWaitPollMs] while another NauSys job
     * holds it, for at most [maxWaitMs]. Returns false (one INFO line) when the budget is
     * spent; the caller then skips its slot without touching a gate it does not own.
     */
    private fun acquireGate(
        job: String,
        maxWaitMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (!nausysBusy.compareAndSet(false, true)) {
            if (System.currentTimeMillis() >= deadline) {
                log.info("NauSYS $job skipped: another NauSys sync still running after ${maxWaitMs / 1000} s wait")
                return false
            }
            sleep(availabilityWaitPollMs)
        }
        return true
    }

    private fun logRateLimitSummary(
        run: String,
        before: NauSysRateLimitStats.Snapshot,
    ) {
        val delta = rateLimitStats.snapshot().since(before)
        log.info("NauSYS $run: 429s this run = ${delta.tooManyRequests}, calls given up (budget/gate) = ${delta.exhausted}")
    }
}
