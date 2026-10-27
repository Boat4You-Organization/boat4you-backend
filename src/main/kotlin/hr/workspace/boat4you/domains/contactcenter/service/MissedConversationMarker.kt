package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Marks the conversations nobody answered in time: a visitor asked for a person and no broker replied within
 * `cc.handoff.minutes` (a reply that came later than that also counts as missed). It only stamps `missed`; the AI taking
 * the conversation back after those minutes is a later phase.
 *
 * This is the only writer of `ai_chat_session.missed` (the column is read-only in JPA). One statement, no transaction of
 * its own beyond that statement, so the setting is read before any transaction opens.
 */
@Service
class MissedConversationMarker(
    private val jdbc: JdbcTemplate,
    private val settings: CcSettings,
) {
    /** Returns the number of conversations newly marked missed. */
    fun markMissed(): Int {
        val minutes = settings.handoffMinutes
        return jdbc.update(
            """
            UPDATE ai_chat_session SET missed = TRUE
            WHERE NOT missed AND handoff_requested_at IS NOT NULL
              AND handoff_requested_at < now() - make_interval(mins => ?)
              AND (first_human_response_at IS NULL OR first_human_response_at > handoff_requested_at + make_interval(mins => ?))
            """.trimIndent(),
            minutes,
            minutes,
        )
    }
}
