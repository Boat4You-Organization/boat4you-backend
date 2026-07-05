package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import hr.workspace.boat4you.domains.external.service.AgencyPresenceReconcileService
import org.springframework.stereotype.Service

@Service
class MmkCatalogueIntegrationService(
    private val mmkCatalogueSyncService: MmkCatalogueSyncService,
    private val mmkAuditedClient: MmkAuditedClient,
    private val agencyPresenceReconcileService: AgencyPresenceReconcileService,
) {
    fun agenciesSync() {
        // Mirror the MMK company list both ways (Mario 5.7.2026): create/refresh what MMK
        // returns, then deactivate what it stopped returning. One fetch feeds both passes,
        // and the reconcile only ever runs on a successfully parsed response.
        val agencies = mmkAuditedClient.getCompanies()
        mmkCatalogueSyncService.updateMmkAgencies(agencies)
        agencyPresenceReconcileService.deactivateAgenciesAbsentFromPartner(
            ExternalSystemEnum.MMK.value,
            agencies.map { it.id }.toSet(),
        )
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
