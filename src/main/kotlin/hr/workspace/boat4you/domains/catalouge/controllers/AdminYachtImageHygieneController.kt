package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.BlockedImageScanDto
import hr.workspace.boat4you.domains.catalouge.dto.LayoutScanDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageHygieneRequest
import hr.workspace.boat4you.domains.catalouge.services.YachtImageHygieneService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Partner-image hygiene (V9_91, owner incident 16.10.2026): the partner's placeholder hidden, layout drawings the file
 * name misses flagged, one image corrected by hand. SYSTEM_ADMIN only, like AdminCatalogueImageController (whose
 * POST /admin/yachts/main-image/fix-layout repairs the card photos in bulk). An unknown image answers 404 code 1602.
 */
@Tag(name = "Yacht image hygiene", description = "Partner placeholders and layout drawings (administrators)")
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminYachtImageHygieneController(
    private val imageHygieneService: YachtImageHygieneService,
) {
    @Operation(
        description =
            "Stored partner images that are the partner's placeholder (\"Picture is not shown due to possible legal issues\"): " +
                "exact size, then SHA-256 of the stored file. dryRun (default true) only lists them; apply hides them and " +
                "moves the card photo of their boats. onlyModelPictures (default true) scans the NauSys model pictures only.",
    )
    @PostMapping("/yachts/images/detect-blocked")
    fun detectBlockedImages(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
        @RequestParam(defaultValue = "true") onlyModelPictures: Boolean,
    ): ResponseEntity<BlockedImageScanDto> = ResponseEntity.ok(imageHygieneService.detectBlocked(dryRun, onlyModelPictures))

    @Operation(
        description =
            "Images of active boats the file name does not call a layout drawing, measured by the visual detector. A dry " +
                "run (dryRun, default true) lists the candidates of a page of limit (default 200, at most 2000) rows after " +
                "afterId (the answer's nextAfterId). dryRun=false requires imageIds (comma-separated, at most 2000): the " +
                "reviewed candidates without the false positives; only those the detector still flags get " +
                "layout_override = true and lose the card photo, the rest come back in skippedImageIds. " +
                "onlyMain (default true): the boats' main images only.",
    )
    @PostMapping("/yachts/images/detect-layout")
    fun detectLayoutImages(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
        @RequestParam(defaultValue = "true") onlyMain: Boolean,
        @RequestParam(defaultValue = "200") limit: Int,
        @RequestParam(defaultValue = "0") afterId: Long,
        @RequestParam(required = false) imageIds: List<Long>?,
    ): ResponseEntity<LayoutScanDto> = ResponseEntity.ok(imageHygieneService.detectLayout(dryRun, onlyMain, limit, afterId, imageIds))

    @Operation(description = "Correct one partner image: layout (true / false overrides the file name), hidden (true / false); a field left out stays")
    @PatchMapping("/yacht-images/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateYachtImage(
        @PathVariable id: Long,
        @RequestBody request: YachtImageHygieneRequest,
    ): ResponseEntity<YachtImageAdminDto> = ResponseEntity.ok(imageHygieneService.update(id, request))
}
