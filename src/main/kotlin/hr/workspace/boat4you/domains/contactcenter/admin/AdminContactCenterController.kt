package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.contactcenter.jpa.ContactStatus
import hr.workspace.boat4you.domains.contactcenter.service.ContactLinker
import hr.workspace.boat4you.domains.contactcenter.service.ContactService
import hr.workspace.boat4you.domains.contactcenter.service.ProactiveMessaging
import hr.workspace.boat4you.domains.contactcenter.service.ProactiveResult
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
 * SYSTEM_ADMIN JSON API of the Contact Center admin (P2, BACKEND-27): conversations, live visitors, contacts and
 * statistics. Replying, closing and deleting stay on the existing `/admin/chat/` endpoints; the AI breaker and settings
 * on `/admin/cc/ai/`. Everything a visitor, the AI or the tracker wrote is returned as data, never as markup (ADMIN-12).
 */
@Controller
@RequestMapping("/admin/cc")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminContactCenterController(
    private val conversations: ConversationQuery,
    private val linker: ContactLinker,
    private val sessions: AiChatSessionRepository,
    private val chat: AiChatService,
    private val live: LiveView,
    private val proactive: ProactiveMessaging,
    private val contactQuery: ContactQuery,
    private val contacts: ContactService,
    private val stats: StatsQuery,
) {
    data class TextRequest(
        val text: String? = null,
    )

    data class ContactPatch(
        val name: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val status: String? = null,
        val note: String? = null,
    )

    data class TagsRequest(
        val tags: List<String>? = null,
    )

    data class MergeRequest(
        val sourceId: Long? = null,
    )

    @GetMapping("/conversations")
    @ResponseBody
    fun conversations(
        @RequestParam(required = false) siteId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "false") needsHuman: Boolean,
        @RequestParam(defaultValue = "false") flaggedOnly: Boolean,
        @RequestParam(required = false) contactStatus: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "true") includeSpam: Boolean,
        @RequestParam(required = false) before: Instant?,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<Any> {
        if (siteId != null && !SiteIds.isKnown(siteId)) return bad("unknown site")
        if (status != null && status !in STATUSES) return bad("unknown status")
        if (contactStatus != null && !isContactStatus(contactStatus)) return bad("unknown contact status")
        val filter =
            ConversationFilter(
                siteId = siteId,
                status = status,
                needsHuman = needsHuman,
                flaggedOnly = flaggedOnly,
                contactStatus = contactStatus,
                tag = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                from = from,
                to = to,
                q = q,
                includeSpam = includeSpam,
                before = before,
                beforeId = beforeId,
                size = size,
            )
        return ResponseEntity.ok(conversations.list(filter))
    }

    @GetMapping("/conversations/unread-count")
    @ResponseBody
    fun unreadCount(): Map<String, Long> = mapOf("count" to conversations.unreadCount())

    /** The conversation, after giving it its contact (ContactLinker), so the card is always one click away. */
    @GetMapping("/conversations/{id}")
    @ResponseBody
    fun conversation(
        @PathVariable id: Long,
    ): ResponseEntity<Any> {
        linker.ensureForSession(id)
        return conversations.detail(id)?.let { ResponseEntity.ok(it) } ?: notFound()
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseBody
    fun read(
        @PathVariable id: Long,
    ): ResponseEntity<Any> = if (conversations.markRead(id)) ResponseEntity.noContent().build() else notFound()

    /** "Preuzmi": the AI stops, the visitor sees nothing; 409 for a LOCKED one (answer it with a reply instead, ADMIN-9). */
    @PostMapping("/conversations/{id}/take-over")
    @ResponseBody
    fun takeOver(
        @PathVariable id: Long,
    ): ResponseEntity<Any> {
        val session = active(id) ?: return notFound()
        return if (chat.takeOver(session)) ResponseEntity.noContent().build() else notAllowed()
    }

    /** "Vrati AI-ju": only HUMAN, never flagged, LOCKED or deleted (BACKEND-4, ADMIN-9); the AI answers a waiting visitor. */
    @PostMapping("/conversations/{id}/return-to-ai")
    @ResponseBody
    fun returnToAi(
        @PathVariable id: Long,
    ): ResponseEntity<Any> {
        val session = active(id) ?: return notFound()
        return if (chat.returnToAi(session)) ResponseEntity.noContent().build() else notAllowed()
    }

    @GetMapping("/live")
    @ResponseBody
    fun live(): List<LiveRow> = live.rows()

    @PostMapping("/live/{visitorKey}/message")
    @ResponseBody
    fun message(
        @PathVariable visitorKey: String,
        @RequestBody req: TextRequest,
    ): ResponseEntity<Any> {
        val result = proactive.enqueueProactive(visitorKey, req.text.orEmpty(), getAuthenticatedUserId())
        val body = mapOf("result" to result.name)
        return if (result == ProactiveResult.INVALID_TEXT) ResponseEntity.badRequest().body(body) else ResponseEntity.ok(body)
    }

    @GetMapping("/contacts")
    @ResponseBody
    fun contacts(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<Any> {
        if (status != null && !isContactStatus(status)) return bad("unknown contact status")
        return ResponseEntity.ok(contactQuery.list(q, status, tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, page, size))
    }

    @GetMapping("/contacts/{id}")
    @ResponseBody
    fun contact(
        @PathVariable id: Long,
    ): ResponseEntity<Any> = contactQuery.card(id)?.let { ResponseEntity.ok(it) } ?: notFound()

    /** A null field stays as it is, a blank one is cleared. */
    @PatchMapping("/contacts/{id}")
    @ResponseBody
    fun update(
        @PathVariable id: Long,
        @RequestBody req: ContactPatch,
    ): ResponseEntity<Any> {
        val updated =
            try {
                contacts.update(id, req.name, req.email, req.phone, req.status, req.note)
            } catch (e: IllegalArgumentException) {
                return bad(e.message ?: "invalid")
            }
        if (!updated) return notFound()
        return contactQuery.card(id)?.let { ResponseEntity.ok(it.contact) } ?: notFound()
    }

    @PutMapping("/contacts/{id}/tags")
    @ResponseBody
    fun tags(
        @PathVariable id: Long,
        @RequestBody req: TagsRequest,
    ): ResponseEntity<Any> {
        val tags =
            try {
                contacts.setTags(id, req.tags.orEmpty())
            } catch (e: IllegalArgumentException) {
                return bad(e.message ?: "invalid")
            } ?: return notFound()
        return ResponseEntity.ok(mapOf("tags" to tags))
    }

    /** Merges [MergeRequest.sourceId] into this contact (the broker's click; never automatic) and returns the merged card. */
    @PostMapping("/contacts/{id}/merge")
    @ResponseBody
    fun merge(
        @PathVariable id: Long,
        @RequestBody req: MergeRequest,
    ): ResponseEntity<Any> {
        val source = req.sourceId ?: return bad("sourceId missing")
        if (source == id) return bad("same contact")
        if (!contacts.merge(id, source)) return notFound()
        return contactQuery.card(id)?.let { ResponseEntity.ok(it) } ?: notFound()
    }

    /** Statistics for [from, to), the last 30 days by default; at most [MAX_RANGE]. */
    @GetMapping("/stats")
    @ResponseBody
    fun stats(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) siteId: String?,
    ): ResponseEntity<Any> {
        if (siteId != null && !SiteIds.isKnown(siteId)) return bad("unknown site")
        val end = to ?: Instant.now()
        val start = from ?: end.minus(DEFAULT_RANGE)
        if (!start.isBefore(end)) return bad("empty range")
        if (Duration.between(start, end) > MAX_RANGE) return bad("range over 400 days")
        return ResponseEntity.ok(stats.stats(start, end, siteId))
    }

    private fun active(id: Long): AiChatSession? = sessions.findById(id).orElse(null)?.takeIf { it.deletedAt == null }

    private fun isContactStatus(s: String) = ContactStatus.entries.any { it.name == s }

    private fun bad(error: String): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to error))

    private fun notFound(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "not_found"))

    private fun notAllowed(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "not_allowed"))

    companion object {
        private val STATUSES =
            setOf(
                AiChatSession.STATUS_AI,
                AiChatSession.STATUS_HUMAN_REQUESTED,
                AiChatSession.STATUS_HUMAN,
                AiChatSession.STATUS_CLOSED,
                AiChatSession.STATUS_LOCKED,
            )
        private val DEFAULT_RANGE: Duration = Duration.ofDays(30)
        private val MAX_RANGE: Duration = Duration.ofDays(400)
    }
}
