package hr.workspace.boat4you.domains.external.nausys.controller

import hr.workspace.boat4you.domains.external.nausys.job.NausysSyncJob
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("data-sync")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/admin/nausys")
class NausysSyncController(
    private val nausysSyncJob: NausysSyncJob,
    private val nauSysCatalogueIntegrationService: NauSysCatalogueIntegrationService,
) {
    // State-changing sync triggers must be POST so they aren't fetched by
    // browsers/proxies/link previews and don't end up cached anywhere
    // (F1-042).
    @PostMapping("/agencies")
    fun agenciesFirstSync() {
        nauSysCatalogueIntegrationService.agenciesFirstSync()
    }

    @PostMapping("/sync")
    fun catalogueSync() {
        nausysSyncJob.runCatalogueSync()
    }

    @PostMapping("/yachts")
    fun yachtsSync() {
        nausysSyncJob.runYachtSync()
    }

    @PostMapping("/offer")
    fun offersSync() {
        nausysSyncJob.runOfferSync()
    }

    @PostMapping("/availability")
    fun availabilitySync() {
        nausysSyncJob.availabilitySync()
    }

    @PostMapping("/duplicate-fix")
    fun duplicateFix() {
        nausysSyncJob.eliminateDuplicateModels()
    }
}
