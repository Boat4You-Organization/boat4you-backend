package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.services.YachtOwnMediaService
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * The crew profile PDF of a boat (V9_92, owner request 17.10.2026), linked from the boat page of all 7 sites as
 * `crewProfileUrl` of /public/yachts/{slug}. Inline, cached a day; 404 when the boat (active) has none.
 */
@Tag(name = "Yachts")
@RestController
@RequestMapping("/public/yachts")
class YachtCrewProfileController(
    private val ownMedia: YachtOwnMediaService,
) {
    @Operation(description = "The boat's crew profile (PDF, inline); 404 when it has none")
    @GetMapping("/{yachtId}/crew-profile.pdf")
    fun getCrewProfile(
        @PathVariable yachtId: String,
    ): ResponseEntity<Resource> {
        val file = SlugUtils.idFromSlug(yachtId)?.let(ownMedia::crewProfile) ?: return ResponseEntity.notFound().build()
        val disposition =
            ContentDisposition
                .inline()
                .let { if (file.fileName.all { it.code < ASCII_LIMIT }) it.filename(file.fileName) else it.filename(file.fileName, StandardCharsets.UTF_8) }
                .build()
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .body(FileSystemResource(file.path))
    }

    private companion object {
        const val ASCII_LIMIT = 128
    }
}
