package hr.workspace.boat4you.domains.contactcenter.controllers

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.rec.RecSettingsStore
import hr.workspace.boat4you.domains.contactcenter.rec.RecSpool
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
 * (the kill switch); 413 over [MAX_BODY_BYTES]; 400 `format` for a body that is neither gzip nor JSON; 507 `quota` while the worker's
 * quota flag is set; 503 when the spool cannot be written; else 204. Every answer is `no-store`.
 */
@RestController
@RequestMapping("/public/cc/v1")
class PublicRecController(
    private val settings: RecSettingsStore,
    private val spool: RecSpool,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/r")
    @Suppress("ReturnCount") // a guard clause per refusal, in the order of the doc above
    fun ingest(http: HttpServletRequest): ResponseEntity<Any> {
        val site = siteOf(http.getHeader("Origin")) ?: return error(HTTP_FORBIDDEN, "origin")
        if (!settings.forSite(site).enabled) return error(HTTP_FORBIDDEN, "off")
        if (http.contentLengthLong > MAX_BODY_BYTES) return error(HTTP_PAYLOAD_TOO_LARGE, "too_large")
        val body = http.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return error(HTTP_PAYLOAD_TOO_LARGE, "too_large")
        val gzip = body.size >= 2 && body[0] == GZIP_0 && body[1] == GZIP_1
        val first = body.firstOrNull { !Char(it.toInt() and 0xFF).isWhitespace() }
        if (!gzip && first != '{'.code.toByte() && first != '['.code.toByte()) return error(HTTP_BAD_REQUEST, "format")
        if (spool.quotaFull()) return error(HTTP_INSUFFICIENT_STORAGE, "quota")
        return try {
            spool.write(site, body, gzip)
            ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
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
            .body(mapOf("enabled" to s.enabled, "sample" to s.samplePct, "mobileSample" to s.mobilePct))
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
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_UNAVAILABLE = 503
        private const val HTTP_INSUFFICIENT_STORAGE = 507
    }
}
