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
        filter.contactId?.let { and("s.contact_id = ?", it) }
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
        return jdbc.query("SELECT $COLUMNS $FROM WHERE $where ORDER BY s.last_activity_at DESC, s.id DESC LIMIT ?", ROW, *args.toTypedArray())
    }

    /** Conversations waiting to be read, for the menu badge. */
    fun unreadCount(): Long = jdbc.queryForObject("SELECT count(*) FROM ai_chat_session WHERE deleted_at IS NULL AND admin_unread", Long::class.java)!!

    /** The conversation with its messages, the visitor's navigation across all sites and its searches; null when missing or deleted. */
    fun detail(id: Long): ConversationDetail? {
        val head =
            jdbc
                .query(
                    "SELECT $COLUMNS, s.referrer, s.page_trail, s.first_response_at, s.first_response_by, s.taken_over_at, " +
                        "s.handoff_requested_at, s.ai_returned_at $FROM WHERE s.id = ? AND s.deleted_at IS NULL",
                    { rs, n ->
                        ConversationDetail(
                            row = ROW.mapRow(rs, n)!!,
                            referrer = rs.getString("referrer"),
                            pageTrail = rs.getString("page_trail"),
                            messages = emptyList(),
                            history = emptyList(),
                            searches = emptyList(),
                            firstResponseAt = rs.instant("first_response_at"),
                            firstResponseBy = rs.getString("first_response_by"),
                            takenOverAt = rs.instant("taken_over_at"),
                            handoffRequestedAt = rs.instant("handoff_requested_at"),
                            aiReturnedAt = rs.instant("ai_returned_at"),
                        )
                    },
                    id,
                ).firstOrNull() ?: return null
        val visitorId = head.row.visitorId
        return head.copy(messages = messages(id), history = visitorId?.let(::history).orEmpty(), searches = searches(id, visitorId))
    }

    /** Clears only the unread flag; status and the metric stamps stay as they are (ADMIN-10). */
    fun markRead(id: Long): Boolean = jdbc.update("UPDATE ai_chat_session SET admin_unread = FALSE WHERE id = ? AND deleted_at IS NULL", id) == 1

    private fun messages(sessionId: Long): List<MessageView> =
        jdbc.query(
            "SELECT * FROM (SELECT id, role, content, payload, created_at, author_user_id FROM ai_chat_message WHERE session_id = ? " +
                "ORDER BY id DESC LIMIT $MAX_MESSAGES) m ORDER BY id",
            { rs, _ ->
                MessageView(
                    rs.getLong("id"),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getString("payload"),
                    rs.instant("created_at")!!,
                    rs.longOrNull("author_user_id"),
                )
            },
            sessionId,
        )

    /** The visitor's newest page views on every site, oldest first. */
    private fun history(visitorId: Long): List<PageViewItem> =
        jdbc.query(
            "SELECT * FROM (SELECT pv.id, v.site_id, pv.path, pv.title, pv.page_type, pv.viewed_at FROM cc_page_view pv " +
                "JOIN cc_visit v ON v.id = pv.visit_id WHERE v.visitor_id = ? ORDER BY pv.viewed_at DESC, pv.id DESC LIMIT $MAX_HISTORY) h " +
                "ORDER BY viewed_at, id",
            { rs, _ -> PageViewItem(rs.getString("site_id"), rs.getString("path"), rs.getString("title"), rs.getString("page_type"), rs.instant("viewed_at")!!) },
            visitorId,
        )

    /** The conversation's own searches plus the visitor's site searches; estimated and flagged rows are left out. Newest first. */
    internal fun searches(
        sessionId: Long?,
        visitorId: Long?,
    ): List<SearchItem> {
        val owners = mutableListOf<String>()
        val args = mutableListOf<Any>()
        sessionId?.let {
            owners += "cs.chat_session_id = ?"
            args += it
        }
        visitorId?.let {
            owners += "(cs.source = 'SITE_SEARCH' AND cs.visit_id IN (SELECT id FROM cc_visit WHERE visitor_id = ?))"
            args += it
        }
        if (owners.isEmpty()) return emptyList()
        return jdbc.query(
            "SELECT source, site_id, location, country_code, start_date, end_date, min_persons, min_cabins, vessel_type, result_count, created_at " +
                "FROM cc_search cs WHERE NOT cs.estimated AND NOT cs.flagged AND (${owners.joinToString(" OR ")}) " +
                "ORDER BY cs.created_at DESC, cs.id DESC LIMIT $MAX_SEARCHES",
            SEARCH_ROW,
            *args.toTypedArray(),
        )
    }

    companion object {
        private const val MAX_MESSAGES = 1000
        private const val MAX_HISTORY = 200
        private const val MAX_SEARCHES = 100
        private const val MAX_PAGE = 200
        private const val MAX_QUERY = 100
        private const val LAST_MESSAGE_CHARS = 140

        private const val SEARCH =
            "(s.visitor_name ILIKE ? ESCAPE '\\' OR s.visitor_email ILIKE ? ESCAPE '\\' " +
                "OR EXISTS (SELECT 1 FROM ai_chat_message sm WHERE sm.session_id = s.id AND sm.content ILIKE ? ESCAPE '\\'))"

        private const val COLUMNS =
            "s.id, s.site_id, s.status, s.locale, s.visitor_name, s.visitor_email, s.phone, s.admin_unread, s.last_activity_at, " +
                "lm.content AS last_message, lm.role AS last_message_role, s.last_seen_at, s.current_page, s.country_code, s.country, s.ip, " +
                "s.flagged, s.lock_reason, s.locked_at, s.missed, s.contact_id, c.status AS contact_status, tg.tags, s.visitor_id"

        private const val FROM =
            "FROM ai_chat_session s LEFT JOIN cc_contact c ON c.id = s.contact_id " +
                "LEFT JOIN LATERAL (SELECT left(m.content, $LAST_MESSAGE_CHARS) AS content, m.role FROM ai_chat_message m " +
                "WHERE m.session_id = s.id ORDER BY m.id DESC LIMIT 1) lm ON TRUE " +
                "LEFT JOIN LATERAL (SELECT array_agg(t.tag ORDER BY t.tag) AS tags FROM cc_contact_tag t WHERE t.contact_id = s.contact_id) tg ON TRUE"

        /** `\`, `%` and `_` become literal text, wrapped as a contains pattern for `ILIKE ? ESCAPE '\'`. */
        fun escapeLike(raw: String): String = "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        internal fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

        internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

        internal fun ResultSet.tags(): List<String> = (getArray("tags")?.array as? Array<*>)?.map { it.toString() }.orEmpty()

        private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

        internal val SEARCH_ROW =
            RowMapper { rs, _ ->
                SearchItem(
                    source = rs.getString("source"),
                    siteId = rs.getString("site_id"),
                    location = rs.getString("location"),
                    countryCode = rs.getString("country_code"),
                    startDate = rs.getDate("start_date")?.toLocalDate(),
                    endDate = rs.getDate("end_date")?.toLocalDate(),
                    minPersons = rs.intOrNull("min_persons"),
                    minCabins = rs.intOrNull("min_cabins"),
                    vesselType = rs.getString("vessel_type"),
                    resultCount = rs.intOrNull("result_count"),
                    at = rs.instant("created_at")!!,
                )
            }

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
                    tags = rs.tags(),
                    visitorId = rs.longOrNull("visitor_id"),
                )
            }
    }
}
