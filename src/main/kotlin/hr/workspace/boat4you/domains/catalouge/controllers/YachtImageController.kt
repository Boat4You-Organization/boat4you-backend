package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.catalouge.services.YachtImageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Duration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
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
        // F1-070: bound width and height before they reach OpenCV.
        // The downstream `Imgproc.resize` allocates `width * height * 3`
        // bytes of native BGR memory; an unbounded `?width=999999` from
        // an anonymous /public caller would request ~3 TB and OOM-kill
        // the JVM. Cap at 4K (4096 px) which is the largest reasonable
        // display target, and reject zero or negative as that would
        // either short-circuit the resize or trip an OpenCV assertion.
        width?.let { requireDimensionInRange("width", it) }
        height?.let { requireDimensionInRange("height", it) }

        val image = yachtImageService.resizeImage(imageId, width, height)
        // Image bytes are immutable per (id, width, height), so they are long-lived
        // cacheable. Set Cache-Control explicitly here: Spring Security's default
        // `no-store` otherwise reaches the CDN/browser and defeats edge caching, so
        // every request re-runs the OpenCV resize on the backend.
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .contentType(MediaType.parseMediaType("image/webp"))
            .body(image)
    }

    private fun requireDimensionInRange(name: String, value: Int) {
        if (value !in 1..MAX_DIMENSION) {
            throw ParameterValidationException(
                mapOf(name to "must be between 1 and $MAX_DIMENSION"),
            )
        }
    }

    companion object {
        private const val MAX_DIMENSION = 4096
    }
}
