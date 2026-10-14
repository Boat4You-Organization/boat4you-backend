package hr.workspace.boat4you.domains.external.controller

import hr.workspace.boat4you.common.exceptions.ResourceNotFound
import hr.workspace.boat4you.domains.external.evidence.ReverifyRun
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.service.PartnerFlipUndoService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin view of the partner reverify runs and the exact undo of a run's status flips (real-price DESIGN.md WS-B).
 * Database only (no partner call), so it is not tied to the scheduler node: both nodes share the tables. Agency ids
 * only, never names. POST for the undo, so a browser prefetch can never fire it.
 */
@RestController
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/admin/reverify")
class AdminReverifyController(
    private val runStore: ReverifyRunStore,
    private val undoService: PartnerFlipUndoService,
) {
    @Operation(summary = "One reverify run: mode, counters, abort reason, escalated agency ids")
    @GetMapping("/runs/{runId}")
    fun getRun(
        @PathVariable runId: Long,
    ): ResponseEntity<ReverifyRun> = ResponseEntity.ok(runStore.find(runId) ?: throw ResourceNotFound())

    @Operation(
        summary = "Undo the status flips of one reverify run",
        description =
            "Restores status / ext_status from partner_offer_flip_log for rows still UNAVAILABLE and unchanged since, " +
                "skipping rows with a live partner RESERVATION/SERVICE or a booking in progress, and clears the hide " +
                "records of the restored periods. Idempotent: a second call restores nothing.",
    )
    @PostMapping("/runs/{runId}/undo")
    fun undoRun(
        @PathVariable runId: Long,
    ): ResponseEntity<PartnerFlipUndoService.UndoResult> {
        runStore.find(runId) ?: throw ResourceNotFound()
        return ResponseEntity.ok(undoService.undoRun(runId))
    }
}
