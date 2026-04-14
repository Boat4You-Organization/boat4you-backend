package hr.workspace.boat4you.domains.external.nausys.controller

import hr.workspace.boat4you.domains.external.nausys.job.NausysSyncJob
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("data-sync")
@RequestMapping("/admin/nausys")
class NausysSyncController(
    private val nausysSyncJob: NausysSyncJob,
    private val nauSysCatalogueIntegrationService: NauSysCatalogueIntegrationService,
) {
    @GetMapping("/agencies")
    fun agenciesFirstSync() {
        nauSysCatalogueIntegrationService.agenciesFirstSync()
    }

    @GetMapping("/sync")
    fun catalogueSync() {
        nausysSyncJob.runCatalogueSync()
    }

    @GetMapping("/yachts")
    fun yachtsSync() {
        nausysSyncJob.runYachtSync()
    }

    @GetMapping("/offer")
    fun offersSync() {
        nausysSyncJob.runOfferSync()
    }

    @GetMapping("/availability")
    fun availabilitySync() {
        nausysSyncJob.availabilitySync()
    }

    @GetMapping("/duplicate-fix")
    fun duplicateFix() {
        nausysSyncJob.eliminateDuplicateModels()
    }
}
