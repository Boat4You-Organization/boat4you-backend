package hr.workspace.boat4you.domains.social.web

import hr.workspace.boat4you.domains.social.jpa.SocialMediaRepository
import hr.workspace.boat4you.domains.social.service.SocialAccountService
import hr.workspace.boat4you.domains.social.service.SocialMediaStore
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The two public pieces of Objave (under the permitAll /public prefix): the OAuth callback each network sends the browser
 * back to (it only redirects to an allowlisted admin address), and the media files the networks fetch by URL.
 */
@RestController
@RequestMapping("/public/social")
class SocialPublicController(
    private val accounts: SocialAccountService,
    private val media: SocialMediaRepository,
    private val store: SocialMediaStore,
) {
    @GetMapping("/oauth/{network}/callback")
    fun callback(
        @PathVariable network: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false) errorDescription: String?,
        @RequestParam(name = "error_reason", required = false) errorReason: String?,
    ): ResponseEntity<Void> {
        val problem = listOfNotNull(errorDescription, errorReason, error).firstOrNull { it.isNotBlank() }
        val target = accounts.finishConnect(network, code, state, problem)
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
    }

    /** `{token}.jpg|mp4|mov` or `{token}.thumb.jpg`. Unguessable token, cached a day, no listing. */
    @GetMapping("/media/{file:.+}")
    fun file(
        @PathVariable file: String,
    ): ResponseEntity<Resource> {
        val thumb = file.endsWith(".thumb.jpg")
        val token = if (thumb) file.removeSuffix(".thumb.jpg") else file.substringBeforeLast('.')
        if (!TOKEN.matches(token)) return ResponseEntity.notFound().build()
        val m = media.findByToken(token) ?: return ResponseEntity.notFound().build()
        val path = (if (thumb) store.thumbnailFile(m) else store.file(m)) ?: return ResponseEntity.notFound().build()
        if (!Files.exists(path)) return ResponseEntity.notFound().build()
        val type = if (thumb) MediaType.IMAGE_JPEG else MediaType.parseMediaType(m.mimeType)
        return ResponseEntity
            .ok()
            .contentType(type)
            .contentLength(Files.size(path))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(path))
    }

    private companion object {
        val TOKEN = Regex("^[A-Za-z0-9]{20,64}$")
    }
}
