package hr.workspace.boat4you.domains.external.nausys.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.nausys.service.NauSysAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class NausysSyncJob(
    private val nauSysYachtOfferIntegrationService: NauSysYachtOfferIntegrationService,
    private val nauSysYachtIntegrationService: NauSysYachtIntegrationService,
    private val nauSysCatalogueIntegrationService: NauSysCatalogueIntegrationService,
    private val nauSysAvailabilityIntegrationService: NauSysAvailabilityIntegrationService,
    private val serviceCallCacheService: ServiceCallCacheService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Syncs all NauSYS catalogue data, including agencies, countries, regions, locations, vessel types, manufacturers, models.
     */
    @Scheduled(cron = "0 0 1 * * ?")
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

    /**
     * Syncs all NauSYS yachts data.
     */
    @Scheduled(cron = "0 30 1 * * ?")
    @SchedulerLock(name = "nausysYachtSync", lockAtMostFor = "PT2H")
    fun runYachtSync() {
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
    }

    /**
     * Backup sync in case main sync fails.
     */
    @Scheduled(cron = "0 15 6,10,15 * * ?")
    @SchedulerLock(name = "nausysYachtBackupSync", lockAtMostFor = "PT2H")
    fun runYachtBackupSync() {
        if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)) {
            log.info("Syncing NauSYS yachts")
            val startTime = System.currentTimeMillis()
            nauSysYachtIntegrationService.yachtSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)
            log.info("Syncing NauSYS yachts took ${System.currentTimeMillis() - startTime} ms")
        }
        if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)) {
            log.info("Syncing NauSYS offers")
            val startTime = System.currentTimeMillis()
            nauSysYachtOfferIntegrationService.yachtOfferSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
            log.info("Syncing NauSYS yacht offers took ${System.currentTimeMillis() - startTime} ms")
        }
    }

    /**
     * DISABLED 2026-06-17 (Mario): offers/prices now sync ONCE per day (the 01:30
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
     * Runs 4x/day (04:20, 10:20, 16:20, 22:20) so a partner-side reservation/option
     * surfaces within a few hours — this is now the PRIMARY freshness engine for the
     * site (the intraday OFFER/price re-sync was retired 2026-06-17; per NauSys docs
     * prices sync 1x/day, occupancy "more often, every few hours"). 04:20 starts
     * after the 01:30 offer sync's PT2H window so the two don't run NauSys API calls
     * in parallel (docs require sequential calls). :20 past the hour, every 6h.
     */
    @Scheduled(cron = "0 20 4,10,16,22 * * *")
    @SchedulerLock(name = "nausysAvailabilitySync", lockAtMostFor = "PT1H")
    fun availabilitySync() {
        log.info("Starting NauSYS availability sync")
        nauSysAvailabilityIntegrationService.syncYachtAvailability()
    }

    fun runOfferSync() {
        log.info("Syncing NauSYS offers")
        val startTimeOffer = System.currentTimeMillis()
        nauSysYachtOfferIntegrationService.yachtOfferSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
        log.info("Syncing NauSYS yacht offers took ${System.currentTimeMillis() - startTimeOffer} ms")
    }
}
