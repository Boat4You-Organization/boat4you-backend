package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import org.slf4j.LoggerFactory
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isEnabled(): Boolean = anthropic.isEnabled()

    @Transactional
    fun createSession(locale: String?): AiChatSession {
        val session = AiChatSession().apply {
            token = UUID.randomUUID().toString()
            this.locale = (locale ?: "en").take(8)
        }
        return sessions.save(session)
    }

    fun getSession(token: String): AiChatSession? = sessions.findByToken(token)

    fun messagesAfter(sessionId: Long, afterId: Long): List<AiChatMessage> =
        messages.findAllBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId, afterId)

    /**
     * Store the visitor message and produce the reply. AI sessions get the
     * assistant; HUMAN sessions just persist the message (broker answers from
     * the inbox) and return an empty reply list.
     */
    @Transactional
    fun handleVisitorMessage(session: AiChatSession, rawContent: String): List<AiChatMessage> {
        val content = rawContent.trim().take(MAX_MESSAGE_CHARS)
        require(content.isNotBlank()) { "empty message" }
        check(messages.countBySessionId(session.id!!) < MAX_MESSAGES_PER_SESSION) { "session message cap reached" }

        val userMsg = save(session.id!!, AiChatMessage.ROLE_USER, content)
        session.lastActivityAt = Instant.now()

        if (session.status != AiChatSession.STATUS_AI) {
            session.adminUnread = true
            sessions.save(session)
            return listOf(userMsg)
        }
        sessions.save(session)

        val reply = try {
            runAssistant(session)
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

    private fun runAssistant(session: AiChatSession): AiChatMessage {
        val history = buildModelMessages(session.id!!)
        var pendingCards: ArrayNode? = null

        var rounds = 0
        while (true) {
            val response = anthropic.createMessage(systemPrompt(session), history, tools.toolDefinitions())
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
        You are the boat4you assistant on www.boat4you.com — a yacht charter booking platform with
        thousands of boats in 40+ countries (sailing yachts, catamarans, motor yachts, gulets),
        online booking, secure payment and a 72-hour free cancellation window after booking.
        Today's date: ${LocalDate.now()}. Site locale of this visitor: ${session.locale}.

        RULES:
        - ALWAYS answer in the visitor's language (match their messages; default to the site locale).
        - Be warm, concise (2-5 short sentences) and practical. One question at a time.
        - Your goal is to guide the visitor to the right boat. Collect: destination, dates
          (charters usually run Saturday to Saturday), group size, boat type, budget — then CALL
          search_yachts as soon as you have at least a country and dates. Results appear to the
          visitor as cards under your message: reference them naturally ("the Lagoon 42 at €X"),
          help compare, and invite them to open a card to see photos and book online.
        - NEVER state a price, availability or boat spec from memory — only from tool results.
          If a search fails or finds nothing, say so honestly and suggest loosening one filter.
        - Do not invent discounts or promises. Do not discuss competitors. Stay on yacht charter.
        - If the visitor wants a human, is upset, or asks about payments/refunds/legal/complaints,
          call request_human_agent — the team replies right here in the chat. Ask for their email
          (save_contact) so the team can also reach them if they leave the page.
        - When the visitor shares their name or email at any point, call save_contact.
    """.trimIndent()

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
        const val MAX_MESSAGE_CHARS = 1000
        const val MAX_MESSAGES_PER_SESSION = 80
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_TOOL_ROUNDS = 4
        const val FAILURE_APOLOGY =
            "Sorry — I hit a technical hiccup just now. Please try again in a moment, or ask for our team and a person will reply here."
    }
}
