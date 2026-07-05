package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import hr.workspace.boat4you.domains.external.service.AgencyPresenceReconcileService
import org.springframework.stereotype.Service

@Service
class NauSysCatalogueIntegrationService(
    private val nauSysCatalogueSyncService: NauSysCatalogueSyncService,
    private val nauSysAuditedClient: NauSysAuditedClient,
    private val agencyPresenceReconcileService: AgencyPresenceReconcileService,
) {
    fun agenciesFirstSync() {
        val agencies = nauSysAuditedClient.allCharterCompanies()
        nauSysCatalogueSyncService.syncAgenciesByVatCode(agencies)
    }

    fun agenciesSync() {
        // Mirror the NauSys company list both ways (Mario 5.7.2026): the create/match pass
        // (formerly the one-time first sync) picks up NEW companies automatically, the update
        // pass refreshes fields, and the reconcile deactivates companies NauSys stopped
        // returning. One fetch feeds all three passes, so the reconcile only ever runs on a
        // successfully parsed response.
        val agencies = nauSysAuditedClient.allCharterCompanies()
        nauSysCatalogueSyncService.syncAgenciesByVatCode(agencies)
        nauSysCatalogueSyncService.updateNausysAgencies(agencies)
        agencyPresenceReconcileService.deactivateAgenciesAbsentFromPartner(
            ExternalSystemEnum.NAUSYS.value,
            agencies.companies.orEmpty().mapNotNull { it.id }.toSet(),
        )
    }

    fun countriesSync() {
        val countries = nauSysAuditedClient.allCountries()
        nauSysCatalogueSyncService.countriesSync(countries)
    }

    fun regionsSync() {
        val regions = nauSysAuditedClient.allRegions()
        nauSysCatalogueSyncService.regionsSync(regions)
    }

    fun locationsSync() {
        val locations = nauSysAuditedClient.allLocations()
        nauSysCatalogueSyncService.locationsSync(locations)
    }

    fun categoriesSync() {
        val yachtCategories = nauSysAuditedClient.allYachtCategories()
        nauSysCatalogueSyncService.categoriesSync(yachtCategories)
    }

    fun manufacturerSync() {
        val manufacturers = nauSysAuditedClient.allYachtBuilders()
        nauSysCatalogueSyncService.manufacturerSync(manufacturers)
    }

    fun modelsSync() {
        val models = nauSysAuditedClient.allYachtModels()
        nauSysCatalogueSyncService.modelsSync(models)
    }

    fun equipmentSync() {
        val equipment = nauSysAuditedClient.allEquipment()
        nauSysCatalogueSyncService.equipmentSync(equipment)
    }

    fun syncServices() {
        val services = nauSysAuditedClient.allServices()
        nauSysCatalogueSyncService.syncServices(services)
    }

    fun seasonsSync() {
        val response = nauSysAuditedClient.allSeasons()
        nauSysCatalogueSyncService.seasonsSync(response)
    }

    fun basesSync() {
        val response = nauSysAuditedClient.allBases()
        nauSysCatalogueSyncService.basesSync(response)
    }

    fun eliminateDuplicateModels() {
        nauSysCatalogueSyncService.eliminateDuplicateModels()
    }
}
