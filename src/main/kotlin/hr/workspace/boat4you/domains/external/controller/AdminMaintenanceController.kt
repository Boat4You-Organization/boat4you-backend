package hr.workspace.boat4you.domains.external.controller

import hr.workspace.boat4you.domains.external.job.RetentionReaperJob
import io.swagger.v3.oas.annotations.Operation
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Manual triggers for maintenance jobs — same pattern as [MmkSyncController]
 * (data-sync profile only, so these live on the scheduler node, POST-only so a
 * browser prefetch can never fire one).
 */
@RestController
@Profile("data-sync")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/admin/maintenance")
class AdminMaintenanceController(
    private val retentionReaperJob: RetentionReaperJob,
) {
    @Operation(
        summary = "Run one bounded retention-reaper pass now",
        description = "Deletes old service_call audit rows and dead past offers (same bounded " +
            "pass the nightly 03:40 job runs). Returns what was deleted; backlogRemains=true " +
            "means the per-run cap was hit and more remains for the next pass.",
    )
    @PostMapping("/retention-reaper")
    fun runRetentionReaper(): ResponseEntity<RetentionReaperJob.ReapSummary> {
        return ResponseEntity.ok(retentionReaperJob.reapOnce())
    }
}
