package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.services.YachtImageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Yacht images", description = "Operations for yacht images")
@RestController
@RequestMapping("/public/image")
class YachtImageController(
    private val yachtImageService: YachtImageService,
) {
    @Operation(description = "Get yacht image by Id and resize it")
    @GetMapping("/{imageId}")
    fun resizeImage(
        @PathVariable imageId: Long,
        @RequestParam width: Int?,
        @RequestParam height: Int?,
    ): ResponseEntity<Resource> {
        val image = yachtImageService.resizeImage(imageId, width, height)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("image/webp"))
            .body(image)
    }
}
