package hr.workspace.boat4you.domains.external.mmk.controller

import hr.workspace.boat4you.domains.external.mmk.service.MmkAvailabilityIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@Profile("data-sync")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/admin/mmk")
class MmkSyncController(
    private val mmkYachtOfferIntegrationServiceAsync: MmkYachtOfferIntegrationServiceAsync,
    private val mmkAvailabilityIntegrationService: MmkAvailabilityIntegrationService,
    private val mmkYachtIntegrationService: MmkYachtIntegrationService,
    private val mmkCatalogueIntegrationService: MmkCatalogueIntegrationService,
    private val mmkYachtOfferIntegrationService: MmkYachtOfferIntegrationService,
) {
    @GetMapping("/sync")
    fun catalouge() {
        mmkCatalogueIntegrationService.countriesSync()
        mmkCatalogueIntegrationService.sailingAreaSync()
        mmkCatalogueIntegrationService.locationsSync()

        mmkCatalogueIntegrationService.agenciesSync()

        // we don't need categories sync as yachtType in synced from yacht
        mmkCatalogueIntegrationService.manufacturersSync()
        // there is no model sync in MMK, as there is no model endpoint. Models are synced with yacht

        mmkCatalogueIntegrationService.equipmentSync()
    }

    @GetMapping("/yachts")
    fun yachts() {
        mmkYachtIntegrationService.yachtSync()
    }

    @GetMapping("/yachts-lang")
    fun yachtsLang() {
        mmkYachtIntegrationService.yachtTranslationsSync()
    }

    @GetMapping("/offer")
    fun offer() {
        mmkYachtOfferIntegrationService.yachtOfferSync()
    }

    @GetMapping("/availability")
    fun availability() {
        mmkAvailabilityIntegrationService.syncYachtAvailability()
    }

    @GetMapping("/offer2")
    fun offer2(): ResponseEntity<String> {
        val startDate = LocalDate.of(2025, 8, 9)
        val endDate = LocalDate.of(2025, 8, 13)
        mmkYachtOfferIntegrationServiceAsync.syncOffersForDateRange(startDate, endDate, null, null, null)
        return ResponseEntity.accepted().body("Offer sync started")
    }
}
