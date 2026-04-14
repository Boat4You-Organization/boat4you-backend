package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import org.springframework.stereotype.Service

@Service
class MmkCatalogueIntegrationService(
    private val mmkCatalogueSyncService: MmkCatalogueSyncService,
    private val mmkAuditedClient: MmkAuditedClient,
) {
    fun agenciesSync() {
        val agencies = mmkAuditedClient.getCompanies()
        mmkCatalogueSyncService.updateMmkAgencies(agencies)
    }

    fun countriesSync() {
        val countries = mmkAuditedClient.getCountries()
        mmkCatalogueSyncService.updateMmkCountries(countries)
    }

    fun sailingAreaSync() {
        val sailingAreas = mmkAuditedClient.getSailingAreas()
        mmkCatalogueSyncService.updateMmkSailigAreas(sailingAreas)
    }

    fun locationsSync() {
        val locations = mmkAuditedClient.getBases()
        mmkCatalogueSyncService.updateMmkLocations(locations)
    }

    fun manufacturersSync() {
        val shipyards = mmkAuditedClient.getShipyards()
        mmkCatalogueSyncService.manufacturerSync(shipyards)
    }

    fun equipmentSync() {
        val equipment = mmkAuditedClient.getEquipment()
        mmkCatalogueSyncService.equipmentSync(equipment)
    }
}
