package hr.workspace.boat4you.domains.external.mmk.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.mmk.service.MmkAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class MmkSyncJob(
    private val mmkCatalogueIntegrationService: MmkCatalogueIntegrationService,
    private val mmkYachtIntegrationService: MmkYachtIntegrationService,
    private val mmkYachtOfferIntegrationService: MmkYachtOfferIntegrationService,
    private val mmkAvailabilityIntegrationService: MmkAvailabilityIntegrationService,
    private val serviceCallCacheService: ServiceCallCacheService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    //    @Scheduled(cron = "0 0 4 * * ?")
    @Scheduled(cron = "0 0 6 * * ?")
    @SchedulerLock(name = "mmkCatalogueSync", lockAtMostFor = "PT1H")
    fun runCatalogueSync() {
        log.info("Starting MMK catalogue sync")

        mmkCatalogueIntegrationService.countriesSync()
        mmkCatalogueIntegrationService.sailingAreaSync()
        mmkCatalogueIntegrationService.locationsSync()

        mmkCatalogueIntegrationService.agenciesSync()

        // we don't need categories sync as yachtType in synced from yacht
        mmkCatalogueIntegrationService.manufacturersSync()
        // there is no model sync in MMK, as there is no model endpoint. Models are synced with yacht

        mmkCatalogueIntegrationService.equipmentSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_CATALOGUE_SYNC)
    }

    @Scheduled(cron = "0 0 7,11,16 * * ?")
    @SchedulerLock(name = "mmkCatalogueBackupSync", lockAtMostFor = "PT1H")
    fun runCatalogueBackupSync() {
        if (!serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_MMK_CATALOGUE_SYNC)) {
            return
        }
        log.info("Starting MMK catalogue sync")

        mmkCatalogueIntegrationService.countriesSync()
        mmkCatalogueIntegrationService.sailingAreaSync()
        mmkCatalogueIntegrationService.locationsSync()
        mmkCatalogueIntegrationService.agenciesSync()
        mmkCatalogueIntegrationService.manufacturersSync()
        mmkCatalogueIntegrationService.equipmentSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_CATALOGUE_SYNC)
    }

    //    @Scheduled(cron = "0 10 4 * * ?")
    @Scheduled(cron = "0 10 6 * * ?")
    @SchedulerLock(name = "mmkYachtSync", lockAtMostFor = "PT1H")
    fun runYachtSync() {
        log.info("Syncing MMK yachts")
        val startTimeYachts = System.currentTimeMillis()
        mmkYachtIntegrationService.yachtSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_SYNC)
        log.info("Syncing MMK yachts took ${System.currentTimeMillis() - startTimeYachts} ms")
    }

    @Scheduled(cron = "0 30 6 * * ?")
    @SchedulerLock(name = "mmkYachtOfferSync", lockAtMostFor = "PT2H")
    fun runYachtOfferSync() {
        log.info("Syncing MMK yacht offers")
        val startTimeOffer = System.currentTimeMillis()
        mmkYachtOfferIntegrationService.yachtOfferSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_OFFER)
        log.info("Syncing MMK yacht offers took ${System.currentTimeMillis() - startTimeOffer} ms")
    }

    @Scheduled(cron = "0 10 7,11,16 * * ?")
    @SchedulerLock(name = "mmkYachtBackupSync", lockAtMostFor = "PT1H")
    fun runYachtBackupSync() {
        if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_SYNC)) {
            log.info("Syncing MMK yachts")
            val startTime = System.currentTimeMillis()
            mmkYachtIntegrationService.yachtSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_SYNC)
            log.info("Syncing MMK yachts took ${System.currentTimeMillis() - startTime} ms")
        }

        if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_OFFER)) {
            log.info("Syncing MMK yacht offers")
            val startTime = System.currentTimeMillis()
            mmkYachtOfferIntegrationService.yachtOfferSync()
            serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_OFFER)
            log.info("Syncing MMK yacht offers took ${System.currentTimeMillis() - startTime} ms")
        }
    }

    //    @Scheduled(cron = "0 0 5 * * ?")
    @Scheduled(cron = "0 20 7 * * ?")
    @SchedulerLock(name = "mmkYachtLangSync", lockAtMostFor = "PT1H")
    fun runYachtLangSync() {
        log.info("Syncing MMK yachts multilingual data")
        val startTime = System.currentTimeMillis()
        mmkYachtIntegrationService.yachtTranslationsSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_LANG_SYNC)
        log.info("Syncing MMK yachts multilingual data took ${System.currentTimeMillis() - startTime} ms")
    }

    @Scheduled(cron = "0 0 8,12,17 * * ?")
    @SchedulerLock(name = "mmkYachtLangBackupSync", lockAtMostFor = "PT1H")
    fun runYachtLangBackupSync() {
        if (!serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_LANG_SYNC)) {
            return
        }
        log.info("Syncing MMK yachts multilingual data")
        val startTime = System.currentTimeMillis()
        mmkYachtIntegrationService.yachtTranslationsSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_LANG_SYNC)
        log.info("Syncing MMK yachts multilingual data took ${System.currentTimeMillis() - startTime} ms")
    }

    // 4x/day (08:40, 12:40, 16:40, 20:40) so partner-side bookings/options land within
    // ~4h — external_reservations is the honest busy source for search, so this is the
    // primary availability-freshness engine. Bumped from 3x 2026-06-17 alongside the
    // NauSys change (offers→1x/day, occupancy→4x/day). Daytime cadence covers the hours
    // agencies actually book; offset from the NauSys availability slots (4/10/16/22) so
    // the two partners' syncs don't pile onto cusma3/cusma4 at the same time.
    @Scheduled(cron = "0 40 8,12,16,20 * * ?")
    @SchedulerLock(name = "mmkAvailabilitySync", lockAtMostFor = "PT1H")
    fun availabilitySync() {
        log.info("Syncing MMK yacht availability")
        val startTime = System.currentTimeMillis()
        mmkAvailabilityIntegrationService.syncYachtAvailability()
        log.info("Syncing MMK yachts availability took ${System.currentTimeMillis() - startTime} ms")
    }
}
