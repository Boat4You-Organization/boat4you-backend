package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.CatalogueImageAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.CatalogueImageFromUrlRequest
import hr.workspace.boat4you.domains.catalouge.dto.CatalogueImageUpdateRequest
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixResultDto
import hr.workspace.boat4you.domains.catalouge.services.AdminCatalogueImageService
import hr.workspace.boat4you.domains.catalouge.services.YachtMainImageFixService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Marina photos of the boat gallery (catalogue_image, design 16.10.2026) and the layout-main repair. SYSTEM_ADMIN only,
 * like AdminYachtController. Validation errors answer 400 code 1102 naming the field (files[i], urls[i].url, credit, ...);
 * an unknown location 404 code 4004, an unknown photo 404 code 1602.
 */
@Tag(name = "Catalogue images", description = "Marina photos for the boat gallery (administrators)")
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminCatalogueImageController(
    private val catalogueImageService: AdminCatalogueImageService,
    private val mainImageFixService: YachtMainImageFixService,
) {
    @Operation(description = "Photos of a location (inactive included), in gallery order")
    @GetMapping("/locations/{id}/images")
    fun getLocationImages(
        @PathVariable id: Long,
    ): ResponseEntity<List<CatalogueImageAdminDto>> = ResponseEntity.ok(catalogueImageService.list(id))

    @Operation(description = "Upload photos of a location (JPEG / PNG / WebP, 10 MB each, stored as WebP); credit required")
    @PostMapping("/locations/{id}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Suppress("LongParameterList") // the multipart form fields
    fun uploadLocationImages(
        @PathVariable id: Long,
        @RequestPart("files", required = false) files: List<MultipartFile>?,
        @RequestParam(required = false) caption: String?,
        @RequestParam(required = false) credit: String?,
        @RequestParam(required = false) sourceUrl: String?,
        @RequestParam(required = false) licence: String?,
    ): ResponseEntity<List<CatalogueImageAdminDto>> = ResponseEntity.ok(catalogueImageService.upload(id, files, caption, credit, sourceUrl, licence))

    @Operation(
        description =
            "Import photos of a location from URLs: the server downloads each one (15 s, 10 MB, image content type, " +
                "public hosts only), validates and stores it. All or nothing; at most 10 per request.",
    )
    @PostMapping("/locations/{id}/images/from-url", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun importLocationImages(
        @PathVariable id: Long,
        @RequestBody request: CatalogueImageFromUrlRequest,
    ): ResponseEntity<List<CatalogueImageAdminDto>> = ResponseEntity.ok(catalogueImageService.importFromUrls(id, request))

    @Operation(description = "Change position, active, caption or credit of a photo (a field left out stays)")
    @PutMapping("/catalogue-images/{id}")
    fun updateCatalogueImage(
        @PathVariable id: Long,
        @RequestBody request: CatalogueImageUpdateRequest,
    ): ResponseEntity<CatalogueImageAdminDto> = ResponseEntity.ok(catalogueImageService.update(id, request))

    @Operation(description = "Delete a photo (row and file); the CDN keeps serving it until purged")
    @DeleteMapping("/catalogue-images/{id}")
    fun deleteCatalogueImage(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        catalogueImageService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        description =
            "Active boats whose card photo (main_image_id) is a layout drawing get their first photo instead. " +
                "dryRun (default true) only lists them.",
    )
    @PostMapping("/yachts/main-image/fix-layout")
    fun fixLayoutMainImages(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
    ): ResponseEntity<MainImageFixResultDto> = ResponseEntity.ok(mainImageFixService.fixLayoutMainImages(dryRun))
}
