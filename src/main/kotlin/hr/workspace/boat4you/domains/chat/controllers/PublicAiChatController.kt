package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.service.AiChatService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Public endpoints for the site chat widget. Hand-written (no OpenAPI), same
 * precedent as PublicUserController. The session token is the only
 * credential — anonymous by design; nginx rate-limits /public/chat/.
 * When no Anthropic key is configured everything returns 503 and the widget
 * hides itself — deploy-safe until the key lands in the env.
 */
@Controller
@RequestMapping("/public/chat")
class PublicAiChatController(
    private val chat: AiChatService,
) {
    data class CreateSessionRequest(val locale: String? = null)
    data class MessageRequest(val content: String? = null)
    data class MessageDto(val id: Long, val role: String, val content: String, val payload: String?)
    data class SessionStateDto(val token: String, val status: String, val messages: List<MessageDto>)

    @PostMapping("/sessions")
    @ResponseBody
    fun createSession(@RequestBody(required = false) request: CreateSessionRequest?): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.createSession(request?.locale)
        return ResponseEntity.ok(SessionStateDto(session.token!!, session.status, emptyList()))
    }

    @PostMapping("/sessions/{token}/messages")
    @ResponseBody
    fun postMessage(@PathVariable token: String, @RequestBody request: MessageRequest): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        if (session.status == AiChatSession.STATUS_CLOSED) return ResponseEntity.status(HttpStatus.GONE).body(mapOf("error" to "closed"))
        val content = request.content?.trim().orEmpty()
        if (content.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "empty"))

        val new = try {
            chat.handleVisitorMessage(session, content)
        } catch (e: IllegalStateException) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(mapOf("error" to "cap"))
        }
        return ResponseEntity.ok(SessionStateDto(token, session.status, new.map(::dto)))
    }

    /** Polling for broker replies once a human owns the session. */
    @GetMapping("/sessions/{token}/messages")
    @ResponseBody
    fun poll(@PathVariable token: String, @RequestParam(defaultValue = "0") afterId: Long): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        val list = chat.messagesAfter(session.id!!, afterId)
        return ResponseEntity.ok(SessionStateDto(token, session.status, list.map(::dto)))
    }

    private fun dto(m: AiChatMessage) = MessageDto(m.id!!, m.role!!, m.content!!, m.payload)

    private fun disabled(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "chat disabled"))

    private fun notFound(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "unknown session"))
}
