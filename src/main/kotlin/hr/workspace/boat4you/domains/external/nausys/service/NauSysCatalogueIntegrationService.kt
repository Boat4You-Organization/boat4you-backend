package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import org.springframework.stereotype.Service

@Service
class NauSysCatalogueIntegrationService(
    private val nauSysCatalogueSyncService: NauSysCatalogueSyncService,
    private val nauSysAuditedClient: NauSysAuditedClient,
) {
    fun agenciesFirstSync() {
        val agencies = nauSysAuditedClient.allCharterCompanies()
        nauSysCatalogueSyncService.syncAgenciesByVatCode(agencies)
    }

    fun agenciesSync() {
        val agencies = nauSysAuditedClient.allCharterCompanies()
        nauSysCatalogueSyncService.updateNausysAgencies(agencies)
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
