package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Instant

/**
 * Broker inbox for the site chat: sessions where the visitor asked for a
 * person surface first; replying flips the session to HUMAN (AI stays out).
 * Security guard (11.10.2026): flagged sessions carry lockReason/lockedAt, can be
 * listed alone (flaggedOnly) and are never deleted; delete is a soft delete.
 */
@Controller
@RequestMapping("/admin/chat")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminAiChatController(
    private val sessions: AiChatSessionRepository,
    private val messages: AiChatMessageRepository,
    private val chat: AiChatService,
) {
    data class SessionDto(
        val id: Long,
        val status: String,
        val locale: String,
        val visitorName: String?,
        val visitorEmail: String?,
        val adminUnread: Boolean,
        val lastActivityAt: Instant,
        val lastMessage: String?,
        val lastSeenAt: Instant?,
        val currentPage: String?,
        val referrer: String?,
        val pageTrail: String?,
        val countryCode: String?,
        val country: String?,
        val ip: String?,
        val flagged: Boolean,
        val lockReason: String?,
        val lockedAt: Instant?,
    )
    data class ReplyRequest(val content: String? = null)
    data class BulkDeleteRequest(val ids: List<Long>? = null)

    @GetMapping("/sessions")
    @ResponseBody
    fun list(
        @RequestParam(defaultValue = "false") needsHumanOnly: Boolean,
        @RequestParam(defaultValue = "false") flaggedOnly: Boolean,
        @RequestParam(defaultValue = "50") size: Int,
    ): List<SessionDto> {
        val page = PageRequest.of(0, size.coerceIn(1, 200))
        val list = when {
            flaggedOnly -> sessions.findAllByDeletedAtIsNullAndFlaggedTrueOrderByLastActivityAtDesc(page)
            // LOCKED = the guard handed the visitor to a live agent, so it waits for a broker too.
            needsHumanOnly -> sessions.findAllByDeletedAtIsNullAndStatusInOrderByLastActivityAtDesc(
                listOf(AiChatSession.STATUS_HUMAN_REQUESTED, AiChatSession.STATUS_HUMAN, AiChatSession.STATUS_LOCKED), page,
            )
            else -> sessions.findAllByDeletedAtIsNullOrderByLastActivityAtDesc(page)
        }
        return list.map { s ->
            val last = messages.findAllBySessionIdOrderByIdAsc(s.id!!).lastOrNull()
            SessionDto(
                s.id!!, s.status, s.locale, s.visitorName, s.visitorEmail,
                s.adminUnread, s.lastActivityAt, last?.content?.take(140),
                s.lastSeenAt, s.currentPage, s.referrer, s.pageTrail,
                s.countryCode, s.country, s.ip,
                s.flagged, s.lockReason, s.lockedAt,
            )
        }
    }

    @GetMapping("/sessions/{id}/messages")
    @ResponseBody
    fun transcript(@PathVariable id: Long): ResponseEntity<List<AiChatMessage>> {
        active(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(messages.findAllBySessionIdOrderByIdAsc(id))
    }

    @PostMapping("/sessions/{id}/reply")
    @ResponseBody
    fun reply(@PathVariable id: Long, @RequestBody request: ReplyRequest): ResponseEntity<AiChatMessage> {
        val session = active(id) ?: return ResponseEntity.notFound().build()
        val content = request.content?.trim().orEmpty()
        if (content.isBlank()) return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(chat.adminReply(session, content, getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }))
    }

    /** Delete one conversation — broker housekeeping (soft delete). A flagged session is evidence: 409. */
    @DeleteMapping("/sessions/{id}")
    @ResponseBody
    fun delete(@PathVariable id: Long): ResponseEntity<Any> {
        val session = active(id) ?: return ResponseEntity.notFound().build()
        if (!chat.deleteSession(session)) return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "flagged"))
        return ResponseEntity.noContent().build()
    }

    /** Delete several conversations at once (checkbox selection in the inbox); flagged ones are skipped. */
    @PostMapping("/sessions/bulk-delete")
    @ResponseBody
    fun bulkDelete(@RequestBody request: BulkDeleteRequest): ResponseEntity<Map<String, Int>> {
        val ids = request.ids.orEmpty().distinct()
        if (ids.isEmpty()) return ResponseEntity.badRequest().build()
        val found = ids.mapNotNull { active(it) }
        val deleted = found.count { chat.deleteSession(it) }
        return ResponseEntity.ok(mapOf("deleted" to deleted, "skipped" to found.size - deleted))
    }

    @PostMapping("/sessions/{id}/close")
    @ResponseBody
    fun close(@PathVariable id: Long): ResponseEntity<Void> {
        val session = active(id) ?: return ResponseEntity.notFound().build()
        chat.closeSession(session)
        return ResponseEntity.noContent().build()
    }

    private fun active(id: Long): AiChatSession? = sessions.findById(id).orElse(null)?.takeIf { it.deletedAt == null }
}
