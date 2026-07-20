package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
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
    )
    data class ReplyRequest(val content: String? = null)

    @GetMapping("/sessions")
    @ResponseBody
    fun list(
        @RequestParam(defaultValue = "false") needsHumanOnly: Boolean,
        @RequestParam(defaultValue = "50") size: Int,
    ): List<SessionDto> {
        val page = PageRequest.of(0, size.coerceIn(1, 200))
        val list = if (needsHumanOnly) {
            sessions.findAllByStatusInOrderByLastActivityAtDesc(
                listOf(AiChatSession.STATUS_HUMAN_REQUESTED, AiChatSession.STATUS_HUMAN), page,
            )
        } else {
            sessions.findAllByOrderByLastActivityAtDesc(page)
        }
        return list.map { s ->
            val last = messages.findAllBySessionIdOrderByIdAsc(s.id!!).lastOrNull()
            SessionDto(
                s.id!!, s.status, s.locale, s.visitorName, s.visitorEmail,
                s.adminUnread, s.lastActivityAt, last?.content?.take(140),
                s.lastSeenAt, s.currentPage, s.referrer, s.pageTrail,
                s.countryCode, s.country, s.ip,
            )
        }
    }

    @GetMapping("/sessions/{id}/messages")
    @ResponseBody
    fun transcript(@PathVariable id: Long): ResponseEntity<List<AiChatMessage>> {
        sessions.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(messages.findAllBySessionIdOrderByIdAsc(id))
    }

    @PostMapping("/sessions/{id}/reply")
    @ResponseBody
    fun reply(@PathVariable id: Long, @RequestBody request: ReplyRequest): ResponseEntity<AiChatMessage> {
        val session = sessions.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val content = request.content?.trim().orEmpty()
        if (content.isBlank()) return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(chat.adminReply(session, content))
    }

    @PostMapping("/sessions/{id}/close")
    @ResponseBody
    fun close(@PathVariable id: Long): ResponseEntity<Void> {
        val session = sessions.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        chat.closeSession(session)
        return ResponseEntity.noContent().build()
    }
}
