package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.service.LiveVisitor
import hr.workspace.boat4you.domains.contactcenter.service.LiveVisitorQuery
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** One person on the sites now with their latest conversation and contact, for the admin live view (P2). */
data class LiveRow(
    val visitor: LiveVisitor,
    val chatSessionId: Long?,
    val chatStatus: String?,
    val contactId: Long?,
    val contactName: String?,
    val adminUnread: Boolean,
)

@Service
class LiveView(
    private val live: LiveVisitorQuery,
    private val jdbc: JdbcTemplate,
) {
    private data class Chat(
        val sessionId: Long?,
        val status: String?,
        val contactId: Long?,
        val contactName: String?,
        val unread: Boolean,
    )

    /**
     * Everyone on the sites now (no bots, no staff), newest beat first, at most [MAX_ROWS] rows, each with the visitor's latest
     * non-deleted conversation. One query for all of them; no traffic (before P3) is an empty list without any query.
     */
    fun rows(): List<LiveRow> {
        val visitors = live.liveSnapshot().sortedByDescending { it.lastBeatAt }.take(MAX_ROWS)
        if (visitors.isEmpty()) return emptyList()
        val ids = visitors.map { it.visitorId }.distinct()
        val chats = mutableMapOf<Long, Chat>()
        jdbc.query(
            "SELECT v.id AS visitor_id, ls.id AS session_id, ls.status, ls.admin_unread, c.id AS contact_id, c.name AS contact_name " +
                "FROM cc_visitor v LEFT JOIN LATERAL (SELECT s.id, s.status, s.admin_unread, s.contact_id FROM ai_chat_session s " +
                "WHERE s.visitor_id = v.id AND s.deleted_at IS NULL ORDER BY s.last_activity_at DESC, s.id DESC LIMIT 1) ls ON TRUE " +
                "LEFT JOIN cc_contact c ON c.id = COALESCE(ls.contact_id, v.contact_id) " +
                "WHERE v.id IN (${ids.joinToString(",") { "?" }})",
            { rs ->
                chats[rs.getLong("visitor_id")] =
                    Chat(
                        rs.getLong("session_id").takeUnless { rs.wasNull() },
                        rs.getString("status"),
                        rs.getLong("contact_id").takeUnless { rs.wasNull() },
                        rs.getString("contact_name"),
                        rs.getBoolean("admin_unread"),
                    )
            },
            *ids.toTypedArray(),
        )
        return visitors.map { v ->
            val c = chats[v.visitorId]
            LiveRow(v, c?.sessionId, c?.status, c?.contactId, c?.contactName, c?.unread ?: false)
        }
    }

    companion object {
        const val MAX_ROWS = 500
    }
}
