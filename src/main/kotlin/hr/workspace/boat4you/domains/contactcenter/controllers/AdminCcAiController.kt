package hr.workspace.boat4you.domains.contactcenter.controllers

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEvent
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEventRepository
import hr.workspace.boat4you.domains.contactcenter.service.AiCircuitBreaker
import hr.workspace.boat4you.domains.contactcenter.service.CcAiSettings
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * SYSTEM_ADMIN JSON API over the AI circuit breaker, the nine `cc.*` AI settings and the security log (design 2.6,
 * BACKEND-27). The breaker lives in memory on the API node, so this is the only way to switch it on a running node;
 * the settings go through [CcAiSettings.update] with its range checks. No admin UI here: P2 builds its screens on it.
 */
@Controller
@RequestMapping("/admin/cc/ai")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCcAiController(
    private val breaker: AiCircuitBreaker,
    private val settings: CcAiSettings,
    private val events: CcSecurityEventRepository,
) {
    data class BreakerRequest(
        val siteId: String? = null,
    )

    data class SettingRequest(
        val key: String? = null,
        val value: String? = null,
    )

    @GetMapping("/breaker")
    @ResponseBody
    fun breaker(): List<AiCircuitBreaker.ScopeState> = breaker.states()

    /** Opens the global breaker (no site) or one site's by hand; it stays open until closed. */
    @PostMapping("/breaker/open")
    @ResponseBody
    fun open(
        @RequestBody req: BreakerRequest,
    ): ResponseEntity<Any> {
        if (!knownOrGlobal(req.siteId)) return unknownSite()
        breaker.open(req.siteId, by())
        return ResponseEntity.ok(breaker.states())
    }

    @PostMapping("/breaker/close")
    @ResponseBody
    fun close(
        @RequestBody req: BreakerRequest,
    ): ResponseEntity<Any> {
        if (!knownOrGlobal(req.siteId)) return unknownSite()
        breaker.close(req.siteId, by())
        return ResponseEntity.ok(breaker.states())
    }

    @GetMapping("/settings")
    @ResponseBody
    fun settings(): CcAiSettings.Values = settings.current()

    /** One `cc.*` AI setting through [CcAiSettings.update] and its specs; 400 for an unknown key or a value its spec refuses. */
    @PutMapping("/settings")
    @ResponseBody
    fun update(
        @RequestBody req: SettingRequest,
    ): ResponseEntity<Any> {
        val key = req.key ?: return invalid()
        if (!settings.update(key, req.value)) return invalid()
        return ResponseEntity.ok(settings.current())
    }

    /** The newest rows of the security log, [limit] 1-500. */
    @GetMapping("/security-events")
    @ResponseBody
    fun events(
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<CcSecurityEvent> = events.findAllByOrderByIdDesc(PageRequest.of(0, limit.coerceIn(1, 500)))

    private fun knownOrGlobal(siteId: String?): Boolean = siteId == null || SiteIds.isKnown(siteId)

    private fun by(): String = SecurityContextHolder.getContext().authentication?.name ?: "admin"

    private fun unknownSite(): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to "unknown site"))

    private fun invalid(): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to "invalid"))
}
