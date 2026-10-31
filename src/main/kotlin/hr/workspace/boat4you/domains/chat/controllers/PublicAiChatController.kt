package hr.workspace.boat4you.domains.chat.controllers

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.chat.service.ChatSecurityGuard
import hr.workspace.boat4you.domains.contactcenter.service.ChatSiteResolver
import hr.workspace.boat4you.domains.contactcenter.service.ChatVisitLinker
import org.slf4j.LoggerFactory
import jakarta.servlet.http.HttpServletRequest
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
 * credential — anonymous by design; nginx and PublicEndpointRateLimiter
 * rate-limit /public/chat/. When no Anthropic key is configured everything
 * returns 503 and the widget hides itself — deploy-safe until the key lands in the env.
 *
 * Security guard contract with the widget (11.10.2026): a message to a locked
 * session or one the guard stops -> 423 {"error":"locked","message":<handoff text>};
 * the session status then reads "LOCKED"; a refused name -> 400 {"error":"invalid_name"}.
 */
@Controller
@RequestMapping("/public/chat")
class PublicAiChatController(
    private val chat: AiChatService,
    private val visits: ChatVisitLinker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class CreateSessionRequest(
        val locale: String? = null,
        val page: String? = null,
        val referrer: String? = null,
        val name: String? = null,
        /** For a server-to-server call without an `Origin` only; a browser's `Origin` header always decides the site. */
        val siteId: String? = null,
        /** P3 widget: the visitor and visit of the tracker, when it runs; today's ChatWidget sends neither (WEB-12). */
        val visitorKey: String? = null,
        val visitKey: String? = null,
    )

    data class MessageRequest(
        val content: String? = null,
        val page: String? = null,
        /** The widget's id for this message; a retry of the same POST carries the same one and gets the first answer back. */
        val clientMessageId: String? = null,
    )
    data class PresenceRequest(val page: String? = null)
    data class MessageDto(val id: Long, val role: String, val content: String, val payload: String?)

    /** [aiPaused]: the AI circuit breaker is open for this site; status codes stay as they were (BACKEND-7). */
    data class SessionStateDto(
        val token: String,
        val status: String,
        val messages: List<MessageDto>,
        val visitorName: String? = null,
        val aiPaused: Boolean = false,
        /** An e-mail or phone is known: the widget's contact form stays hidden (31.10.2026). */
        val hasContact: Boolean = false,
    )

    @PostMapping("/sessions")
    @ResponseBody
    fun createSession(
        @RequestBody(required = false) request: CreateSessionRequest?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        // nginx forwards the client address as X-Real-IP; remoteAddr is the proxy.
        val clientIp = httpRequest.getHeader("X-Real-IP")?.takeIf { it.isNotBlank() } ?: httpRequest.remoteAddr
        val session =
            try {
                chat.createSession(
                    request?.locale,
                    request?.name,
                    clientIp,
                    ChatSiteResolver.resolve(httpRequest.getHeader("Origin"), request?.siteId),
                )
            } catch (e: AiChatService.InvalidNameException) {
                return invalidName()
            }
        chat.updatePresence(session, request?.page, request?.referrer)
        linkVisit(session, request)
        return ResponseEntity.ok(SessionStateDto(session.token!!, session.status, emptyList(), session.visitorName, chat.isAiPaused(session), hasContact(session)))
    }

    /**
     * Points a new session at the widget's visit (P3 task 9). Only two well-formed UUIDs are passed on; the linker checks
     * that the visit is live, belongs to that visitor and to the session's site (P1b). A failure never changes the reply.
     */
    @Suppress("TooGenericExceptionCaught") // the chat must answer even when the link fails
    private fun linkVisit(
        session: AiChatSession,
        request: CreateSessionRequest?,
    ) {
        val visitorKey = request?.visitorKey?.trim()?.takeIf { UUID_V4.matches(it) } ?: return
        val visitKey = request.visitKey?.trim()?.takeIf { UUID_V4.matches(it) } ?: return
        try {
            visits.linkChatSession(session.id!!, session.siteId, visitorKey, visitKey)
        } catch (e: Exception) {
            log.warn("CC: chat session {} not linked to its visit: {}", session.id, e.toString())
        }
    }

    data class NameRequest(val name: String? = null)

    /** Late name capture for sessions created before the pre-chat name step. */
    @PostMapping("/sessions/{token}/name")
    @ResponseBody
    fun setName(@PathVariable token: String, @RequestBody request: NameRequest): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        val name = request.name?.trim().orEmpty()
        if (name.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "empty"))
        return when (chat.setVisitorName(session, name)) {
            AiChatService.NameOutcome.OK -> ResponseEntity.ok(mapOf("ok" to true))
            AiChatService.NameOutcome.INVALID -> invalidName()
            AiChatService.NameOutcome.LOCKED -> locked(ChatSecurityGuard.handoffText(session.locale))
        }
    }

    data class ContactRequest(
        val email: String? = null,
        val phone: String? = null,
    )

    /** The contact form under IRIS's first boats (31.10.2026): an e-mail and/or phone so the team can send an offer. */
    @PostMapping("/sessions/{token}/contact")
    @ResponseBody
    fun setContact(
        @PathVariable token: String,
        @RequestBody request: ContactRequest,
    ): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        return when (chat.setVisitorContact(session, request.email, request.phone)) {
            AiChatService.ContactOutcome.OK -> ResponseEntity.ok(mapOf("ok" to true))
            AiChatService.ContactOutcome.INVALID_EMAIL -> ResponseEntity.badRequest().body(mapOf("error" to "invalid_email"))
            AiChatService.ContactOutcome.INVALID_PHONE -> ResponseEntity.badRequest().body(mapOf("error" to "invalid_phone"))
            AiChatService.ContactOutcome.EMPTY -> ResponseEntity.badRequest().body(mapOf("error" to "empty"))
            AiChatService.ContactOutcome.LOCKED -> locked(ChatSecurityGuard.handoffText(session.locale))
        }
    }

    /** Widget heartbeat — who is live on the site and which page they're on. */
    @PostMapping("/sessions/{token}/presence")
    @ResponseBody
    fun presence(@PathVariable token: String, @RequestBody(required = false) request: PresenceRequest?): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        chat.updatePresence(session, request?.page)
        return ResponseEntity.ok(mapOf("ok" to true))
    }

    @PostMapping("/sessions/{token}/messages")
    @ResponseBody
    fun postMessage(@PathVariable token: String, @RequestBody request: MessageRequest): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        // A retried POST (same client id) is answered from the store: no reopen, no second model call, no cap check.
        // The rate limiter sits in front of this controller, so a retry still counts against it.
        val clientMessageId = AiChatService.normalizeClientMessageId(request.clientMessageId)
        chat.replayIfDuplicate(session, clientMessageId)?.let { return respond(token, session, it) }
        // A closed thread reopens on the visitor's next message — same conversation,
        // same owner (human if a broker ever replied), never a silent AI reset.
        chat.reopenIfClosed(session)
        val content = request.content?.trim().orEmpty()
        if (content.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "empty"))

        val outcome = try {
            chat.handleVisitorMessage(session, content, request.page?.trim()?.take(500), clientMessageId)
        } catch (e: IllegalStateException) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(mapOf("error" to "cap"))
        }
        return respond(token, session, outcome)
    }

    private fun respond(
        token: String,
        session: AiChatSession,
        outcome: AiChatService.MessageOutcome,
    ): ResponseEntity<Any> =
        when (outcome) {
            is AiChatService.MessageOutcome.Replied ->
                ResponseEntity.ok(SessionStateDto(token, session.status, outcome.messages.map(::dto), session.visitorName, outcome.aiPaused, hasContact(session)))
            is AiChatService.MessageOutcome.Locked -> locked(outcome.handoffText)
            AiChatService.MessageOutcome.Busy -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "busy"))
        }

    /** Polling for broker replies once a human owns the session. */
    @GetMapping("/sessions/{token}/messages")
    @ResponseBody
    fun poll(@PathVariable token: String, @RequestParam(defaultValue = "0") afterId: Long): ResponseEntity<Any> {
        if (!chat.isEnabled()) return disabled()
        val session = chat.getSession(token) ?: return notFound()
        val list = chat.messagesAfter(session.id!!, afterId)
        return ResponseEntity.ok(SessionStateDto(token, session.status, list.map(::dto), session.visitorName, chat.isAiPaused(session), hasContact(session)))
    }

    private fun dto(m: AiChatMessage) = MessageDto(m.id!!, m.role!!, m.content!!, m.payload)

    private fun invalidName(): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to "invalid_name"))

    private fun hasContact(session: AiChatSession) = !session.visitorEmail.isNullOrBlank() || !session.phone.isNullOrBlank()

    private fun locked(handoffText: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.LOCKED).body(mapOf("error" to "locked", "message" to handoffText))

    private fun disabled(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "chat disabled"))

    private fun notFound(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "unknown session"))

    private companion object {
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
