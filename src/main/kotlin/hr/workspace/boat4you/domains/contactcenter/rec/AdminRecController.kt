package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * The recording switches of the seven sites in the admin (P6 R1.6, SYSTEM_ADMIN): on or off, and which share of visits with
 * analytics consent is recorded, of all and of mobile ones. Off is the kill switch: the widget stops starting the recorder within
 * 5 minutes and the ingest refuses the site's payloads at once.
 */
@Controller
@RequestMapping("/admin/cc/rec")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminRecController(
    private val settings: RecSettingsStore,
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

    companion object {
        private val PCT = 0..100
    }
}
