package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageOrderRequest
import hr.workspace.boat4you.domains.catalouge.dto.YachtLookupDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtMediaDto
import hr.workspace.boat4you.domains.catalouge.services.PartnerImageNotDeletableException
import hr.workspace.boat4you.domains.catalouge.services.YachtBusyException
import hr.workspace.boat4you.domains.catalouge.services.YachtOwnMediaService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
 * Our own photos, film and crew profile on ANY boat (V9_92, owner request 17.10.2026: gulet Allure 11769, a
 * partner-synced boat) - the partner syncs never delete or move what is added here. SYSTEM_ADMIN only, like
 * AdminYachtController (whose /admin/custom-yachts endpoints keep the custom-boat behaviour). Every media call answers
 * the boat's media ([YachtMediaDto]). Validation errors answer 400 code 1102 naming the field (files[i], file, imageIds,
 * videoUrl, main), an unknown boat 404, an unknown image 404 code 1602, deleting a partner picture 409 PARTNER_IMAGE, a
 * boat a partner sync holds longer than [YachtOwnMediaService.LOCK_TIMEOUT] 409 YACHT_BUSY (nothing stored, try again).
 * PATCH /admin/yacht-images/{id} {"main": true} (AdminYachtImageHygieneController) picks the main image among them.
 */
@Tag(name = "Yacht media", description = "Our own photos, film and crew profile on any boat (administrators)")
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminYachtMediaController(
    private val media: YachtOwnMediaService,
) {
    @Operation(description = "Find boats by id or name (public or partner name), the exact id first, then active boats; limit 1-50, default 20")
    @GetMapping("/yachts/lookup")
    fun lookup(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<YachtLookupDto>> = ResponseEntity.ok(media.lookup(q, limit))

    @Operation(description = "A boat's images (position order, hidden included, source PARTNER / OWN), main image, film and crew profile")
    @GetMapping("/yachts/{id}/media")
    fun getMedia(
        @PathVariable id: Long,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.media(id))

    @Operation(
        description =
            "Upload our own photos of any boat (multipart \"files\", at most 20; JPEG / PNG / WebP, 10 MB each, stored as WebP): " +
                "after our photos already there, before the partner's. All or nothing.",
    )
    @PostMapping("/yachts/{id}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImages(
        @PathVariable id: Long,
        @RequestPart("files", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.addImages(id, files))

    @Operation(description = "Upload our own photo as the boat's main image (multipart \"file\", or \"mainImage\"): first in the gallery, the card photo")
    @PostMapping("/yachts/{id}/main-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadMainImage(
        @PathVariable id: Long,
        @RequestPart("file", required = false) file: MultipartFile?,
        @RequestPart("mainImage", required = false) mainImage: MultipartFile?,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.addMainImage(id, file ?: mainImage))

    @Operation(
        description =
            "New gallery order: imageIds first in that order, the boat's other images after them in today's order. " +
                "The next partner sync puts the partner's pictures behind our own photos again.",
    )
    @PutMapping("/yachts/{id}/images/order", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reorderImages(
        @PathVariable id: Long,
        @RequestBody request: YachtImageOrderRequest,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.reorder(id, request.imageIds))

    @Operation(description = "Delete one of our own photos (row and file); a partner picture answers 409 (hide it with PATCH /admin/yacht-images/{id} instead)")
    @DeleteMapping("/yachts/{id}/images/{imageId}")
    fun deleteImage(
        @PathVariable id: Long,
        @PathVariable imageId: Long,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.deleteImage(id, imageId))

    @Operation(
        description =
            "The boat's film {\"videoUrl\": \"...\"}: a YouTube or Vimeo URL (watch / youtu.be / embed, vimeo.com/<id> / " +
                "player.vimeo.com/video/<id>); {\"videoUrl\": null} removes it, a body without videoUrl is refused",
    )
    @PatchMapping("/yachts/{id}/media", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateMedia(
        @PathVariable id: Long,
        @RequestBody request: Map<String, String?>,
    ): ResponseEntity<YachtMediaDto> {
        // an empty body must not wipe the film: the key is required, null removes it
        if (VIDEO_URL !in request) throw ParameterValidationException(mapOf(VIDEO_URL to "required (null removes the film)"))
        return ResponseEntity.ok(media.setVideo(id, request[VIDEO_URL]))
    }

    @Operation(description = "Upload the boat's crew profile (multipart \"file\", PDF, 15 MB); replaces the previous one")
    @PostMapping("/yachts/{id}/crew-profile", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadCrewProfile(
        @PathVariable id: Long,
        @RequestPart("file", required = false) file: MultipartFile?,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.setCrewProfile(id, file))

    @Operation(description = "Remove the boat's crew profile")
    @DeleteMapping("/yachts/{id}/crew-profile")
    fun deleteCrewProfile(
        @PathVariable id: Long,
    ): ResponseEntity<YachtMediaDto> = ResponseEntity.ok(media.deleteCrewProfile(id))

    /**
     * 409: DELETE of a partner picture (it would come back with the next sync), or a boat a partner sync holds until its
     * agency commits (minutes; nothing was stored, a retry is safe).
     */
    @ExceptionHandler(PartnerImageNotDeletableException::class, YachtBusyException::class)
    fun conflict(e: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                if (e is YachtBusyException) {
                    mapOf("status" to "YACHT_BUSY", "message" to "${e.message} - nothing was saved")
                } else {
                    mapOf("status" to "PARTNER_IMAGE", "message" to "only our own photos can be deleted: a partner picture comes back with the next sync, hide it instead")
                },
            )

    private companion object {
        const val VIDEO_URL = "videoUrl"
    }
}
