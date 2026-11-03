package hr.workspace.boat4you.domains.contactcenter.controllers

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.rec.RecFiles
import hr.workspace.boat4you.domains.contactcenter.rec.RecSettingsStore
import hr.workspace.boat4you.domains.contactcenter.rec.RecSpool
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.net.URI
import java.time.Duration

/**
 * The session recorder's endpoints (P6 R1.3): `POST /public/cc/v1/r` takes one payload of the clarity fork and streams it into the
 * spool untouched (cusma2 never parses a recording), `GET /public/cc/v1/rec-config` tells the widget whether and how much to record.
 *
 * `/r`: the `Origin` host decides the site (403 `origin` for any other host); 403 `off` while the site's recording is switched off
 * (the kill switch); 507 `quota` while the worker's quota flag is set (before the body is read; rec-config then says off, so the
 * recorders stop); 413 over [MAX_BODY_BYTES]; 400 `format` for a body that is neither gzip nor JSON; 503 `busy` while all spool
 * writers wait on the share, 503 `unavailable` when the spool cannot be written; else 204. Every answer is `no-store`.
 *
 * `GET /public/cc/v1/replay-assets/{host}/{sha256}.{ext}` (P6 R2): the archived CSS and fonts of the seven sites, which the admin
 * serves as admin.boat4you.com/replay-assets/ (its CSP allows only its own styles and fonts). Public content of our own sites,
 * stored by content: cached for a year, with a CSP of its own so a file opened directly can never run anything.
 */
@RestController
@RequestMapping("/public/cc/v1")
class PublicRecController(
    private val settings: RecSettingsStore,
    private val spool: RecSpool,
    private val files: RecFiles,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/r")
    @Suppress("ReturnCount") // a guard clause per refusal, in the order of the doc above
    fun ingest(http: HttpServletRequest): ResponseEntity<Any> {
        val site = siteOf(http.getHeader("Origin")) ?: return error(HTTP_FORBIDDEN, "origin")
        if (!settings.forSite(site).enabled) return error(HTTP_FORBIDDEN, "off")
        if (spool.quotaFull()) return error(HTTP_INSUFFICIENT_STORAGE, "quota")
        if (http.contentLengthLong > MAX_BODY_BYTES) return error(HTTP_PAYLOAD_TOO_LARGE, "too_large")
        val body = http.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return error(HTTP_PAYLOAD_TOO_LARGE, "too_large")
        val gzip = body.size >= 2 && body[0] == GZIP_0 && body[1] == GZIP_1
        val first = body.firstOrNull { !Char(it.toInt() and 0xFF).isWhitespace() }
        if (!gzip && first != '{'.code.toByte() && first != '['.code.toByte()) return error(HTTP_BAD_REQUEST, "format")
        return try {
            spool.write(site, body, gzip)
            ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
        } catch (e: RecSpool.SpoolBusyException) {
            error(HTTP_UNAVAILABLE, "busy")
        } catch (e: IOException) {
            log.warn("rec spool write failed for {}: {}", site, e.toString())
            error(HTTP_UNAVAILABLE, "unavailable")
        }
    }

    /** Whether the widget starts the recorder on [siteId], and for which share of visits (all of them with analytics consent). */
    @GetMapping("/rec-config")
    fun config(
        @RequestParam(required = false) siteId: String?,
    ): ResponseEntity<Any> {
        if (siteId == null || !SiteIds.isKnown(siteId)) return error(HTTP_BAD_REQUEST, "site")
        val s = settings.forSite(siteId)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(CONFIG_MAX_AGE).cachePublic())
            .body(mapOf("enabled" to (s.enabled && !spool.quotaFull()), "sample" to s.samplePct, "mobileSample" to s.mobilePct))
    }

    @GetMapping("/replay-assets/{host}/{file}")
    fun replayAsset(
        @PathVariable host: String,
        @PathVariable file: String,
    ): ResponseEntity<Any> {
        val type = ASSET_NAME.matchEntire(file)?.groupValues?.get(1)?.lowercase()?.let(ASSET_TYPES::get)
        if (type == null || SiteIds.HOSTS.keys.none { "www.$it" == host }) return error(HTTP_NOT_FOUND, "not_found")
        val bytes =
            try {
                files.readAsset("assets/$host/$file", MAX_ASSET_BYTES)
            } catch (e: RecFiles.BusyException) {
                return error(HTTP_UNAVAILABLE, "busy")
            } ?: return error(HTTP_NOT_FOUND, "not_found")
        return ResponseEntity
            .ok()
            .contentType(type)
            .cacheControl(CacheControl.maxAge(ASSET_MAX_AGE).cachePublic().immutable())
            .header("Content-Security-Policy", ASSET_CSP)
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }

    private fun siteOf(origin: String?): String? = origin?.let { runCatching { URI(it).host }.getOrNull() }?.let(SiteIds::fromHost)

    private fun error(
        status: Int,
        error: String,
    ): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to error))

    companion object {
        const val MAX_BODY_BYTES = 1_048_576
        private val CONFIG_MAX_AGE: Duration = Duration.ofMinutes(5)
        private const val GZIP_0 = 0x1f.toByte()
        private const val GZIP_1 = 0x8b.toByte()
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val MAX_ASSET_BYTES = 5_000_000L
        private val ASSET_MAX_AGE: Duration = Duration.ofDays(365)
        private const val ASSET_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:"
        private val ASSET_NAME = Regex("^[0-9a-f]{64}\\.([A-Za-z0-9]{1,8})$")
        private val ASSET_TYPES =
            mapOf(
                "css" to MediaType("text", "css"),
                "woff2" to MediaType("font", "woff2"),
                "woff" to MediaType("font", "woff"),
                "ttf" to MediaType("font", "ttf"),
                "otf" to MediaType("font", "otf"),
                "png" to MediaType.IMAGE_PNG,
                "jpg" to MediaType.IMAGE_JPEG,
                "jpeg" to MediaType.IMAGE_JPEG,
                "gif" to MediaType.IMAGE_GIF,
                "webp" to MediaType("image", "webp"),
                "avif" to MediaType("image", "avif"),
            )
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_UNAVAILABLE = 503
        private const val HTTP_INSUFFICIENT_STORAGE = 507
    }
}
