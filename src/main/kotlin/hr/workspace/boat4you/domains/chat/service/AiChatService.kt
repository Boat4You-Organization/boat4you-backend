package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The boat4you site concierge. Visitors chat anonymously (token in
 * localStorage); the assistant answers from live fleet data via tools and
 * hands the thread to a human broker on request (admin inbox). The Anthropic
 * call runs synchronously in the request — a few seconds; the widget shows a
 * typing indicator meanwhile.
 *
 * Guardrails: max message length + per-session message cap, tool loop capped
 * at 4 rounds, prices only ever come from search_yachts results.
 */
@Service
class AiChatService(
    private val sessions: AiChatSessionRepository,
    private val messages: AiChatMessageRepository,
    private val anthropic: AnthropicClient,
    private val tools: AiChatToolExecutor,
    private val objectMapper: ObjectMapper,
    private val emailService: EmailService,
    private val geoIp: GeoIpService,
    @Value("\${chat.notify-email:info@boat4you.com}")
    private val notifyEmail: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isEnabled(): Boolean = anthropic.isEnabled()

    @Transactional
    fun createSession(locale: String?, name: String? = null, ip: String? = null): AiChatSession {
        val geo = geoIp.countryOf(ip)
        val session = AiChatSession().apply {
            token = UUID.randomUUID().toString()
            this.locale = (locale ?: "en").take(8)
            this.visitorName = name?.trim()?.take(120)?.ifBlank { null }
            this.ip = ip?.take(45)
            this.countryCode = geo?.code
            this.country = geo?.name
        }
        return sessions.save(session)
    }

    fun getSession(token: String): AiChatSession? = sessions.findByToken(token)

    /** Broker housekeeping — drop a conversation for good (Mario 5.8.2026). */
    @Transactional
    fun deleteSession(session: AiChatSession) {
        messages.deleteAllBySessionId(session.id!!)
        sessions.delete(session)
    }

    /** Late name capture — legacy sessions predate the pre-chat name step (Mario 30.7.2026). */
    fun setVisitorName(session: AiChatSession, name: String) {
        session.visitorName = name.trim().take(120).ifBlank { return }
        sessions.save(session)
    }

    fun messagesAfter(sessionId: Long, afterId: Long): List<AiChatMessage> =
        messages.findAllBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId, afterId)

    /**
     * Store the visitor message and produce the reply. AI sessions get the
     * assistant; HUMAN sessions just persist the message (broker answers from
     * the inbox) and return an empty reply list.
     */
    @Transactional
    fun handleVisitorMessage(session: AiChatSession, rawContent: String, page: String? = null): List<AiChatMessage> {
        val content = rawContent.trim().take(MAX_MESSAGE_CHARS)
        require(content.isNotBlank()) { "empty message" }
        check(messages.countBySessionId(session.id!!) < MAX_MESSAGES_PER_SESSION) { "session message cap reached" }

        val userMsg = save(session.id!!, AiChatMessage.ROLE_USER, content)
        session.lastActivityAt = Instant.now()
        updatePresence(session, page)

        if (session.status != AiChatSession.STATUS_AI) {
            // First message of a new burst (unread was already seen/answered) pings the
            // broker again — Mario can't watch the inbox all day. While unread stays
            // true no further mail goes out, so a typing visitor sends one mail, not ten.
            val firstOfBurst = !session.adminUnread
            session.adminUnread = true
            sessions.save(session)
            if (firstOfBurst) notifyBroker(session, content, "Nova poruka u chatu #${session.id}")
            return listOf(userMsg)
        }
        sessions.save(session)

        val reply = try {
            runAssistant(session, page)
        } catch (e: Exception) {
            log.warn("chat assistant failed for session ${session.id}: ${e.message}")
            save(
                session.id!!,
                AiChatMessage.ROLE_ASSISTANT,
                FAILURE_APOLOGY,
            )
        }
        return listOf(userMsg, reply)
    }

    /** Broker reply from the admin inbox — takes ownership of the session. */
    @Transactional
    fun adminReply(session: AiChatSession, content: String): AiChatMessage {
        val msg = save(session.id!!, AiChatMessage.ROLE_ADMIN, content.trim().take(MAX_MESSAGE_CHARS))
        session.status = AiChatSession.STATUS_HUMAN
        session.adminUnread = false
        session.lastActivityAt = Instant.now()
        sessions.save(session)
        return msg
    }

    @Transactional
    fun closeSession(session: AiChatSession) {
        session.status = AiChatSession.STATUS_CLOSED
        session.adminUnread = false
        sessions.save(session)
    }

    // ---------------------------------------------------------------- internals

    private fun runAssistant(session: AiChatSession, page: String? = null): AiChatMessage {
        val history = buildModelMessages(session.id!!)
        var pendingCards: ArrayNode? = null

        // "Why doesn't the chat see which yacht I'm on?" (Mario 20.7.2026) — the widget
        // sends the visitor's current URL; on a /boat/ page we inject that yacht's live
        // facts so "this boat" questions (specs, equipment, the page's dates) just work.
        val system = systemPrompt(session) + (tools.pageContext(page)?.let { "\n\n$it" } ?: "")

        var rounds = 0
        while (true) {
            val response = anthropic.createMessage(system, history, tools.toolDefinitions())
            val contentBlocks = response.path("content")
            val stopReason = response.path("stop_reason").asText()

            if (stopReason != "tool_use" || rounds >= MAX_TOOL_ROUNDS) {
                val text = contentBlocks.filter { it.path("type").asText() == "text" }
                    .joinToString("\n") { it.path("text").asText() }
                    .ifBlank { FAILURE_APOLOGY }
                val payload = pendingCards?.let {
                    objectMapper.writeValueAsString(objectMapper.createObjectNode().set<JsonNode>("yachts", it))
                }
                return save(session.id!!, AiChatMessage.ROLE_ASSISTANT, text, payload)
            }

            // Tool round: append the assistant turn verbatim, execute every
            // requested tool, append one user turn with the tool_result blocks.
            rounds++
            (history as ArrayNode).add(
                objectMapper.createObjectNode().apply {
                    put("role", "assistant")
                    set<JsonNode>("content", contentBlocks)
                },
            )
            val results = objectMapper.createArrayNode()
            for (block in contentBlocks) {
                if (block.path("type").asText() != "tool_use") continue
                val outcome = executeTool(session, block.path("name").asText(), block.path("input"))
                if (outcome.cards != null) pendingCards = outcome.cards
                results.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "tool_result")
                        put("tool_use_id", block.path("id").asText())
                        put("content", outcome.resultForModel)
                    },
                )
            }
            history.add(
                objectMapper.createObjectNode().apply {
                    put("role", "user")
                    set<JsonNode>("content", results)
                },
            )
        }
    }

    private fun executeTool(session: AiChatSession, name: String, input: JsonNode): AiChatToolExecutor.ToolOutcome =
        when (name) {
            "search_yachts" -> tools.searchYachts(input)
            "request_human_agent" -> {
                session.status = AiChatSession.STATUS_HUMAN_REQUESTED
                session.adminUnread = true
                sessions.save(session)
                val lastVisitorLine = messages.findAllBySessionIdOrderByIdAsc(session.id!!)
                    .lastOrNull { it.role == AiChatMessage.ROLE_USER }?.content ?: ""
                notifyBroker(session, lastVisitorLine, "Posjetitelj traži živu osobu — chat #${session.id}")
                AiChatToolExecutor.ToolOutcome(
                    """{"ok":true,"note":"The boat4you team has been notified and will reply right here in this chat. Tell the visitor that, and ask for their email via save_contact if you do not have it yet."}""",
                )
            }
            "save_contact" -> {
                input.path("name").asText("").ifBlank { null }?.let { session.visitorName = it.take(120) }
                input.path("email").asText("").ifBlank { null }?.let { session.visitorEmail = it.take(255) }
                sessions.save(session)
                AiChatToolExecutor.ToolOutcome("""{"ok":true}""")
            }
            else -> AiChatToolExecutor.ToolOutcome("""{"error":"unknown tool"}""")
        }

    /** Transcript -> Messages API format. ADMIN lines are folded in as user-side context. */
    private fun buildModelMessages(sessionId: Long): ArrayNode {
        val all = messages.findAllBySessionIdOrderByIdAsc(sessionId).takeLast(MAX_HISTORY_MESSAGES)
        val arr = objectMapper.createArrayNode()
        for (m in all) {
            val role = if (m.role == AiChatMessage.ROLE_ASSISTANT) "assistant" else "user"
            val text = when (m.role) {
                AiChatMessage.ROLE_ADMIN -> "[boat4you agent wrote]: ${m.content}"
                else -> m.content ?: ""
            }
            // Merge consecutive same-role turns (API requires alternation).
            val last = arr.lastOrNull()
            if (last != null && last.path("role").asText() == role) {
                (last as com.fasterxml.jackson.databind.node.ObjectNode)
                    .put("content", last.path("content").asText() + "\n" + text)
            } else {
                arr.add(objectMapper.createObjectNode().apply { put("role", role); put("content", text) })
            }
        }
        return arr
    }

    private fun systemPrompt(session: AiChatSession): String = """
        You are IRIS, the boat4you assistant on www.boat4you.com — a yacht charter booking platform with
        thousands of boats in 40+ countries (sailing yachts, catamarans, motor yachts, gulets),
        online booking, secure payment and a 72-hour free cancellation window after booking.
        Today's date: ${LocalDate.now()}. Site locale of this visitor: ${session.locale}.
        ${session.visitorName?.let { "The visitor's name is $it — greet and address them by name." } ?: ""}

        Your name is IRIS — introduce yourself as IRIS in your first greeting.

        RULES:
        - ALWAYS answer in the visitor's language (match their messages; default to the site locale).
        - Be warm, concise (2-5 short sentences) and practical. One question at a time.
        - PLAIN TEXT ONLY — the chat window renders no markdown, so never use **, *, #, or
          bullet syntax; asterisks would show literally. Use short plain sentences and dashes.
        - Your goal is to guide the visitor to the right boat. Collect: destination, dates
          (charters usually run Saturday to Saturday), group size, boat type, budget — then CALL
          search_yachts as soon as you have at least a country and dates. Results appear to the
          visitor as cards under your message: reference them naturally ("the Lagoon 42 at €X"),
          help compare, and invite them to open a card to see photos and book online.
        - When the visitor names a city, marina, island or region (Split, Trogir, Athens...),
          pass it as `location` so results come from THAT area — never answer a Split request
          with boats from the other end of the country. When they require equipment (air
          conditioning, watermaker, wifi...), pass it as `equipment`. Check the result's
          searchedArea and mention it; if a boat's base differs from the asked place, say so.
        - Results are one page of a bigger list: totalAvailable is the real match count, so
          NEVER claim "that's all there is" from one page. If the visitor asks for pricier,
          luxury or premium options, search again with sortByPrice=desc (and minTotalPriceEur
          above what you already showed).
        - NEVER state a price, availability or boat spec from memory — only from tool results.
          If a search fails or finds nothing, say so honestly and suggest loosening one filter.
        - Do not invent discounts or promises. Do not discuss competitors. Stay on yacht charter.
        - If the visitor wants a human, is upset, or asks about payments/refunds/legal/complaints,
          call request_human_agent — the team replies right here in the chat. Ask for their email
          (save_contact) so the team can also reach them if they leave the page.
        - When the visitor shares their name or email at any point, call save_contact.
        - If a CURRENT PAGE block is present below, the visitor is looking at that yacht right
          now — "this boat" means that yacht. Answer its spec/equipment questions from the
          block; if something isn't listed there, say you're not sure instead of guessing.
    """.trimIndent()

    /**
     * A returning visitor writing into a session the broker closed continues the SAME
     * thread instead of being reset to a fresh AI conversation (Mario 20.7.2026): back
     * to HUMAN when a broker ever replied here (adminUnread + email fire via the normal
     * message path), back to AI otherwise.
     */
    @Transactional
    fun reopenIfClosed(session: AiChatSession) {
        if (session.status != AiChatSession.STATUS_CLOSED) return
        val hadHuman = messages.findAllBySessionIdOrderByIdAsc(session.id!!)
            .any { it.role == AiChatMessage.ROLE_ADMIN }
        session.status = if (hadHuman) AiChatSession.STATUS_HUMAN else AiChatSession.STATUS_AI
        sessions.save(session)
    }

    /**
     * Widget heartbeat (JivoChat parity, Mario 20.7.2026): stamps "seen now" + the page
     * the visitor is on, and keeps a short trail of recent pages so the broker knows
     * who they're talking to and what the visitor has been looking at.
     */
    @Transactional
    fun updatePresence(session: AiChatSession, page: String?, referrer: String? = null) {
        session.lastSeenAt = Instant.now()
        if (!referrer.isNullOrBlank() && session.referrer.isNullOrBlank()) {
            session.referrer = referrer.take(500)
        }
        val cleanPage = page?.trim()?.take(500)
        if (!cleanPage.isNullOrBlank() && cleanPage != session.currentPage) {
            session.currentPage = cleanPage
            val trail = runCatching {
                objectMapper.readValue(session.pageTrail ?: "[]", Array<String>::class.java).toMutableList()
            }.getOrDefault(mutableListOf())
            if (trail.lastOrNull() != cleanPage) trail.add(cleanPage)
            session.pageTrail = objectMapper.writeValueAsString(trail.takeLast(PAGE_TRAIL_CAP))
        }
        sessions.save(session)
    }

    /**
     * Fire-and-forget broker email (Mario can't watch the inbox all day). Own thread +
     * runCatching: a mail hiccup must never delay or break the visitor's chat reply.
     */
    private fun notifyBroker(session: AiChatSession, lastMessage: String, subject: String) {
        // Template renders the message via th:utext — escape + keep line breaks.
        val safeMessage = lastMessage.take(400)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "<br />")
        val vars = mapOf(
            "sessionId" to session.id,
            "visitorName" to (session.visitorName ?: ""),
            "visitorEmail" to (session.visitorEmail ?: ""),
            "country" to (session.country ?: ""),
            "currentPage" to (session.currentPage ?: ""),
            "lastMessage" to safeMessage,
            "currentYear" to LocalDate.now().year.toString(),
            "adminUrl" to "https://admin.boat4you.com/chat",
        )
        Thread({
            runCatching {
                emailService.sendEmail(
                    recipients = listOf(notifyEmail),
                    subject = subject,
                    templateName = "email/chatBrokerNotification",
                    variables = vars,
                )
            }.onFailure { log.warn("chat broker notification mail failed for session ${session.id}: ${it.message}") }
        }, "chat-notify-${session.id}").start()
    }

    private fun save(sessionId: Long, role: String, content: String, payload: String? = null): AiChatMessage =
        messages.save(
            AiChatMessage().apply {
                this.sessionId = sessionId
                this.role = role
                this.content = content
                this.payload = payload
            },
        )

    companion object {
        const val PAGE_TRAIL_CAP = 20
        const val MAX_MESSAGE_CHARS = 1000
        const val MAX_MESSAGES_PER_SESSION = 80
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_TOOL_ROUNDS = 4
        const val FAILURE_APOLOGY =
            "Sorry — I hit a technical hiccup just now. Please try again in a moment, or ask for our team and a person will reply here."
    }
}
