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
     * Syncs NauSYS availability data for all yachts coming from NauSYS agencies.
     * Triggers at the in hourly intervals, at 20 minutes past the hour.
     * 20 minutes past the hour is chosen to avoid overlapping with the catalogue sync at 3:00 AM.
     */
    @Scheduled(cron = "0 20 3,12 * * *")
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
