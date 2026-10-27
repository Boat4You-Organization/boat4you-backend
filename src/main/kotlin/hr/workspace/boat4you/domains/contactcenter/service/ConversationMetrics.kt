package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import java.time.Instant

/**
 * The response-time stamps of one chat conversation (spec 2.3). Each one is set once and never moved: the first
 * response is whoever answered first, the first human response is the first broker reply to a visitor who had written,
 * the takeover is the first broker reply of any kind, and the handoff is the first request for a person.
 *
 * These only change the in-memory [AiChatSession]; writing the stamp to the database is the caller's job. After the model
 * call that must be a conditional UPDATE ([hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository.stampFirstResponse]),
 * never a save of a session loaded before the model ran.
 */
object ConversationMetrics {
    /** The AI answered. Returns true when this was the conversation's first response, i.e. when it stamped. */
    fun onAiReply(
        session: AiChatSession,
        at: Instant,
    ): Boolean {
        if (session.firstResponseAt != null) return false
        session.firstResponseAt = at
        session.firstResponseBy = AiChatSession.RESPONSE_BY_AI
        return true
    }

    /**
     * A broker replied. The takeover is stamped always; the human response only when the visitor had written something
     * ([visitorHasWritten]), because a greeting into an empty conversation answers nobody. A human reply is the first
     * response only if nobody answered before.
     */
    fun onHumanReply(
        session: AiChatSession,
        at: Instant,
        visitorHasWritten: Boolean,
    ) {
        if (session.takenOverAt == null) session.takenOverAt = at
        if (!visitorHasWritten) return
        if (session.firstHumanResponseAt == null) session.firstHumanResponseAt = at
        if (session.firstResponseAt == null) {
            session.firstResponseAt = at
            session.firstResponseBy = AiChatSession.RESPONSE_BY_HUMAN
        }
    }

    /** The visitor asked for a person; the missed-conversation clock starts at the first such request. */
    fun onHandoffRequested(
        session: AiChatSession,
        at: Instant,
    ) {
        if (session.handoffRequestedAt == null) session.handoffRequestedAt = at
    }
}
