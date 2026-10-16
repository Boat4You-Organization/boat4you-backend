package hr.workspace.boat4you.domains.catalouge.publicslug

import hr.workspace.boat4you.common.exceptions.ResourceNotFound
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Frozen public boat slugs (V9_90, owner rule 16.10.2026: a boat's URL never changes again). Database only, so not tied
 * to the scheduler node. POST only, so a browser prefetch can never fire one; both are idempotent.
 */
@Tag(name = "Yacht Management", description = "Yacht management operations for administrators")
@RestController
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequestMapping("/admin/yachts")
class AdminYachtPublicSlugController(
    private val publicSlugService: YachtPublicSlugService,
) {
    @Operation(
        summary = "Freeze the public slug of every boat that has none",
        description =
            "Stores the slug each boat is served under today (no URL changes, no lastmod moves). dryRun=true (default) " +
                "only counts and shows a sample. Rows a sync is writing are skipped and reported as remaining - run again. " +
                "NauSys shell rows (no model yet, inactive) are left for the sync and only counted (shells).",
    )
    @PostMapping("/public-slug/backfill")
    fun backfill(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
    ): ResponseEntity<YachtPublicSlugService.BackfillSummary> = ResponseEntity.ok(publicSlugService.backfill(dryRun, getAuthenticatedUserId()))

    @Operation(
        summary = "Recompute one boat's public slug from its current name and model (owner-approved exception)",
        description =
            "The old URL keeps resolving by its trailing id and redirects to the new one; when the URL changes the " +
                "boat's sitemap lastmod moves. 409 = a sync is writing the boat right now, nothing changed - try again.",
    )
    @PostMapping("/{id}/public-slug/refresh")
    fun refresh(
        @PathVariable id: Long,
    ): ResponseEntity<YachtPublicSlugService.RefreshResult> =
        try {
            ResponseEntity.ok(publicSlugService.refresh(id, getAuthenticatedUserId()) ?: throw ResourceNotFound())
        } catch (e: YachtPublicSlugService.BoatBusyException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
}
