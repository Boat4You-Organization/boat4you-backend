package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.jpa.DeviceClass
import hr.workspace.boat4you.domains.contactcenter.jpa.PageType
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Duration
import java.time.Instant

/**
 * The recording switches of the seven sites in the admin (P6 R1.6, SYSTEM_ADMIN): on or off, and which share of visits with
 * analytics consent is recorded, of all and of mobile ones. Off is the kill switch: the widget stops starting the recorder within
 * 5 minutes and the ingest refuses the site's payloads at once.
 *
 * The player (P6 R2): the recordings list, a recording's details and timeline marks, its raw file (gzip as stored on cusma3,
 * every view audited in `rec_view_audit`, 503 while the NFS share is stalled) and the archived stylesheets it needs.
 */
@Controller
@RequestMapping("/admin/cc/rec")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminRecController(
    private val settings: RecSettingsStore,
    private val query: RecQuery,
    private val files: RecFiles,
) {
    data class SettingRequest(
        val enabled: Boolean? = null,
        val samplePct: Int? = null,
        val mobilePct: Int? = null,
    )

    @GetMapping("/settings")
    @ResponseBody
    fun settings(): List<RecSiteSetting> = settings.all()

    @PutMapping("/settings/{siteId}")
    @ResponseBody
    fun update(
        @PathVariable siteId: String,
        @RequestBody req: SettingRequest,
    ): ResponseEntity<Any> {
        val enabled = req.enabled
        val sample = req.samplePct
        val mobile = req.mobilePct
        if (!SiteIds.isKnown(siteId) || enabled == null || sample == null || mobile == null || sample !in PCT || mobile !in PCT) {
            return ResponseEntity.badRequest().body(mapOf("error" to "invalid"))
        }
        settings.update(RecSiteSetting(siteId, enabled, sample, mobile), getAuthenticatedUserId())
        return ResponseEntity.ok(settings.forSite(siteId))
    }

    @GetMapping("/page-views")
    @ResponseBody
    @Suppress("LongParameterList", "ReturnCount") // one parameter per filter of the list, a guard clause per refusal
    fun pageViews(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) siteId: String?,
        @RequestParam(required = false) device: String?,
        @RequestParam(required = false) pageType: String?,
        @RequestParam(defaultValue = "false") rageOnly: Boolean,
        @RequestParam(defaultValue = "false") noInquiry: Boolean,
        @RequestParam(required = false) visitId: Long?,
        @RequestParam(required = false) conversationId: Long?,
        @RequestParam(required = false) before: Instant?,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<Any> {
        if (siteId != null && !SiteIds.isKnown(siteId)) return bad("site")
        if (device != null && DeviceClass.entries.none { it.name == device }) return bad("device")
        if (pageType != null && PageType.entries.none { it.name == pageType }) return bad("pageType")
        val end = to ?: Instant.now()
        val start = from ?: end.minus(DEFAULT_RANGE)
        if (!start.isBefore(end) || Duration.between(start, end) > MAX_RANGE) return bad("range")
        val filter = RecFilter(start, end, siteId, device, pageType, rageOnly, noInquiry, visitId, conversationId, before, beforeId, size)
        return ResponseEntity.ok(query.list(filter))
    }

    @GetMapping("/page-views/{id}")
    @ResponseBody
    fun pageView(
        @PathVariable id: Long,
    ): ResponseEntity<Any> = query.detail(id)?.let { ResponseEntity.ok<Any>(it) } ?: notFound()

    /** The recording as stored (gzip NDJSON, one payload per line); the browser unzips it. Every view is audited. */
    @GetMapping("/page-views/{id}/raw")
    @ResponseBody
    fun raw(
        @PathVariable id: Long,
    ): ResponseEntity<Any> {
        val path = query.filePath(id) ?: return notFound()
        val bytes =
            try {
                files.read(path)
            } catch (e: RecFiles.BusyException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "busy"))
            } ?: return notFound()
        query.audit(id, getAuthenticatedUserId())
        return ResponseEntity
            .ok()
            .contentType(NDJSON)
            .header("Content-Encoding", "gzip")
            .cacheControl(CacheControl.noStore())
            .body(bytes)
    }

    data class AssetsRequest(
        val siteId: String? = null,
        val paths: List<String>? = null,
    )

    /** The archived copies (`/replay-assets/…`) of the stylesheets a recording names. */
    @PostMapping("/assets")
    @ResponseBody
    fun assets(
        @RequestBody req: AssetsRequest,
    ): ResponseEntity<Any> {
        val siteId = req.siteId?.takeIf(SiteIds::isKnown) ?: return bad("site")
        return ResponseEntity.ok(query.assets(siteId, req.paths.orEmpty()))
    }

    private fun bad(error: String): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to error))

    private fun notFound(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "not_found"))

    companion object {
        private val PCT = 0..100
        private val DEFAULT_RANGE: Duration = Duration.ofDays(7)
        private val MAX_RANGE: Duration = Duration.ofDays(400)
        private val NDJSON = MediaType("application", "x-ndjson")
    }
}
