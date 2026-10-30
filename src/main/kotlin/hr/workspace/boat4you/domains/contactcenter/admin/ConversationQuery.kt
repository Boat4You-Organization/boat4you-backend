package hr.workspace.boat4you.domains.contactcenter.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Read side of the admin conversations (P2). One SQL per call: the newest message and the contact's tags come from lateral
 * joins, never one query per row. The default list is every non-deleted conversation by last activity (ADMIN-2).
 */
@Service
class ConversationQuery(
    internal val jdbc: JdbcTemplate,
) {
    fun list(filter: ConversationFilter): List<ConversationRow> {
        val where = StringBuilder("s.deleted_at IS NULL")
        val args = mutableListOf<Any>()

        fun and(
            clause: String,
            vararg values: Any,
        ) {
            where.append(" AND ").append(clause)
            args.addAll(values)
        }
        filter.siteId?.let { and("s.site_id = ?", it) }
        filter.status?.let { and("s.status = ?", it) }
        if (filter.needsHuman) and("s.status IN ('HUMAN_REQUESTED', 'HUMAN', 'LOCKED')")
        if (filter.flaggedOnly) and("(s.flagged OR s.status = 'LOCKED')")
        filter.contactStatus?.let { and("c.status = ?", it) }
        filter.tag?.let { and("EXISTS (SELECT 1 FROM cc_contact_tag ft WHERE ft.contact_id = s.contact_id AND ft.tag = ?)", it) }
        filter.from?.let { and("s.last_activity_at >= ?", Timestamp.from(it)) }
        filter.to?.let { and("s.last_activity_at < ?", Timestamp.from(it)) }
        if (!filter.includeSpam) and("(c.status IS NULL OR c.status <> 'SPAM')")
        filter.q
            ?.trim()
            ?.take(MAX_QUERY)
            ?.takeIf { it.isNotEmpty() }
            ?.let { escapeLike(it) }
            ?.let { and(SEARCH, it, it, it) }
        if (filter.before != null && filter.beforeId != null) {
            and("(s.last_activity_at, s.id) < (?, ?)", Timestamp.from(filter.before), filter.beforeId)
        }
        args.add(filter.size.coerceIn(1, MAX_PAGE))
        return jdbc.query("$SELECT WHERE $where ORDER BY s.last_activity_at DESC, s.id DESC LIMIT ?", ROW, *args.toTypedArray())
    }

    /** Conversations waiting to be read, for the menu badge. */
    fun unreadCount(): Long = jdbc.queryForObject("SELECT count(*) FROM ai_chat_session WHERE deleted_at IS NULL AND admin_unread", Long::class.java)!!

    companion object {
        private const val MAX_PAGE = 200
        private const val MAX_QUERY = 100
        private const val LAST_MESSAGE_CHARS = 140

        private const val SEARCH =
            "(s.visitor_name ILIKE ? ESCAPE '\\' OR s.visitor_email ILIKE ? ESCAPE '\\' " +
                "OR EXISTS (SELECT 1 FROM ai_chat_message sm WHERE sm.session_id = s.id AND sm.content ILIKE ? ESCAPE '\\'))"

        internal const val SELECT =
            "SELECT s.id, s.site_id, s.status, s.locale, s.visitor_name, s.visitor_email, s.phone, s.admin_unread, s.last_activity_at, " +
                "lm.content AS last_message, lm.role AS last_message_role, s.last_seen_at, s.current_page, s.country_code, s.country, s.ip, " +
                "s.flagged, s.lock_reason, s.locked_at, s.missed, s.contact_id, c.status AS contact_status, tg.tags, s.visitor_id " +
                "FROM ai_chat_session s LEFT JOIN cc_contact c ON c.id = s.contact_id " +
                "LEFT JOIN LATERAL (SELECT left(m.content, $LAST_MESSAGE_CHARS) AS content, m.role FROM ai_chat_message m " +
                "WHERE m.session_id = s.id ORDER BY m.id DESC LIMIT 1) lm ON TRUE " +
                "LEFT JOIN LATERAL (SELECT array_agg(t.tag ORDER BY t.tag) AS tags FROM cc_contact_tag t WHERE t.contact_id = s.contact_id) tg ON TRUE"

        /** `\`, `%` and `_` become literal text, wrapped as a contains pattern for `ILIKE ? ESCAPE '\'`. */
        fun escapeLike(raw: String): String = "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        internal fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

        internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

        internal val ROW =
            RowMapper { rs, _ ->
                ConversationRow(
                    id = rs.getLong("id"),
                    siteId = rs.getString("site_id"),
                    status = rs.getString("status"),
                    locale = rs.getString("locale"),
                    visitorName = rs.getString("visitor_name"),
                    visitorEmail = rs.getString("visitor_email"),
                    phone = rs.getString("phone"),
                    adminUnread = rs.getBoolean("admin_unread"),
                    lastActivityAt = rs.instant("last_activity_at")!!,
                    lastMessage = rs.getString("last_message"),
                    lastMessageRole = rs.getString("last_message_role"),
                    lastSeenAt = rs.instant("last_seen_at"),
                    currentPage = rs.getString("current_page"),
                    countryCode = rs.getString("country_code"),
                    country = rs.getString("country"),
                    ip = rs.getString("ip"),
                    flagged = rs.getBoolean("flagged"),
                    lockReason = rs.getString("lock_reason"),
                    lockedAt = rs.instant("locked_at"),
                    missed = rs.getBoolean("missed"),
                    contactId = rs.longOrNull("contact_id"),
                    contactStatus = rs.getString("contact_status"),
                    tags = (rs.getArray("tags")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
                    visitorId = rs.longOrNull("visitor_id"),
                )
            }
    }
}
