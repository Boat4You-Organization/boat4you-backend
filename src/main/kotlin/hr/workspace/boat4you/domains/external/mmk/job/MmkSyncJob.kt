package hr.workspace.boat4you.domains.external.mmk.job

import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.mmk.service.MmkAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.service.ServiceCallCacheService
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

    //    @Scheduled(cron = "0 0 7,11,16 * * ?")
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
    fun runYachtSync() {
        log.info("Syncing MMK yachts")
        val startTimeYachts = System.currentTimeMillis()
        mmkYachtIntegrationService.yachtSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_SYNC)
        log.info("Syncing MMK yachts took ${System.currentTimeMillis() - startTimeYachts} ms")
    }

    @Scheduled(cron = "0 30 6 * * ?")
    fun runYachtOfferSync() {
        log.info("Syncing MMK yacht offers")
        val startTimeOffer = System.currentTimeMillis()
        mmkYachtOfferIntegrationService.yachtOfferSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_OFFER)
        log.info("Syncing MMK yacht offers took ${System.currentTimeMillis() - startTimeOffer} ms")
    }

    //    @Scheduled(cron = "0 10 7,11,16 * * ?")
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
    fun runYachtLangSync() {
        log.info("Syncing MMK yachts multilingual data")
        val startTime = System.currentTimeMillis()
        mmkYachtIntegrationService.yachtTranslationsSync()
        serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_YACHT_LANG_SYNC)
        log.info("Syncing MMK yachts multilingual data took ${System.currentTimeMillis() - startTime} ms")
    }

    //    @Scheduled(cron = "0 0 8,12,17 * * ?")
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

    @Scheduled(cron = "0 10 8 * * ?")
    fun availabilitySync() {
        log.info("Syncing MMK yacht availability")
        val startTime = System.currentTimeMillis()
        mmkAvailabilityIntegrationService.syncYachtAvailability()
        log.info("Syncing MMK yachts availability took ${System.currentTimeMillis() - startTime} ms")
    }
}
