package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsResponse
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtRequest
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtResponse
import hr.workspace.boat4you.domains.catalouge.dto.IdDto
import hr.workspace.boat4you.domains.catalouge.services.YachtMutationService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PagedModel
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

@Tag(name = "Yacht Management", description = "Yacht management operations for administrators")
@RestController
@RequestMapping("/admin/custom-yachts")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminYachtController(
    private val yachtMutationService: YachtMutationService,
    private val yachtQueryingService: YachtQueryingService,
) {
    @Operation(description = "Fetch all custom boat4you yachts")
    @GetMapping
    fun getAllYachts(
        @RequestParam(required = false) name: String?,
        pageable: Pageable,
    ): ResponseEntity<PagedModel<CustomYachtResponse>> {
        return ResponseEntity.ok(PagedModel(yachtQueryingService.getCustomYachts(name, pageable)))
    }

    @Operation(description = "Get custom yacht details by ID")
    @GetMapping("/{id}")
    fun getCustomYachtDetails(
        @PathVariable id: Long,
    ): ResponseEntity<CustomYachtDetailsResponse> {
        return ResponseEntity.ok(yachtQueryingService.getCustomYachtDetails(id))
    }

    @Operation(description = "Create a custom boat4you yacht")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createYacht(
        @RequestPart customYachtRequest: CustomYachtRequest,
        @RequestPart("mainImage") mainImage: MultipartFile?,
        @RequestPart("images") images: List<MultipartFile>?,
        @RequestPart("pdf") pdfFile: MultipartFile?,
    ): ResponseEntity<CustomYachtDetailsResponse> {
        return ResponseEntity.ok(yachtMutationService.createYacht(customYachtRequest, mainImage, images, pdfFile))
    }

    @Operation(description = "Attach a pdf file to a custom boat4you yacht")
    @PostMapping("/{id}/pdf", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun attachPdfToYacht(
        @PathVariable id: Long,
        @RequestPart("pdfFile") pdfFile: MultipartFile,
    ): ResponseEntity<Void> {
        yachtMutationService.attachPdfFile(id, pdfFile)
        return ResponseEntity.noContent().build()
    }

    @Operation(description = "Add main image to a custom boat4you yacht")
    @PostMapping("/{id}/main-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun addMainImageToYacht(
        @PathVariable id: Long,
        @RequestPart("mainImage") mainImage: MultipartFile,
    ): ResponseEntity<IdDto> {
        val result = yachtMutationService.addMainImageToYacht(id, mainImage)
        return ResponseEntity.ok(result)
    }

    @Operation(description = "Add images to a custom boat4you yacht")
    @PostMapping("/{id}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun addImagesToYacht(
        @PathVariable id: Long,
        @RequestPart("images") images: List<MultipartFile>,
    ): ResponseEntity<Set<IdDto>> {
        val result = yachtMutationService.addImagesToYacht(id, images)
        return ResponseEntity.ok(result)
    }

    @Operation(description = "Update a custom boat4you yacht")
    @PutMapping("/{id}")
    fun updateYacht(
        @PathVariable id: Long,
        @RequestBody customYachtRequest: CustomYachtRequest,
    ): ResponseEntity<CustomYachtDetailsResponse> {
        return ResponseEntity.ok(yachtMutationService.updateYacht(id, customYachtRequest))
    }

    @Operation(description = "Delete custom boat image")
    @DeleteMapping("/{id}/images/{imageId}")
    fun deleteYachtImage(
        @PathVariable id: Long,
        @PathVariable imageId: Long,
    ): ResponseEntity<Void> {
        yachtMutationService.deleteYachtImage(id, imageId)
        return ResponseEntity.noContent().build()
    }

    @Operation(description = "Delete pdf attachment")
    @DeleteMapping("/{id}/pdf")
    fun deletePdfAttachment(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        yachtMutationService.deletePdfFile(id)
        return ResponseEntity.noContent().build()
    }

    @Operation(description = "Delete custom boat4you yacht")
    @DeleteMapping("/{id}")
    fun deleteYacht(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        yachtMutationService.deleteYacht(id)
        return ResponseEntity.noContent().build()
    }
}
