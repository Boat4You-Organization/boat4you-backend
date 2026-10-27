package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.service.AiChatService
import hr.workspace.boat4you.domains.chat.service.GeoIpService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** One proactive message as it was written to the chat: [id] is the `ai_chat_message` row, [createdAt] the delivery time. */
data class DeliveredMessage(
    val id: Long,
    val text: String,
    val createdAt: Instant,
)

/** What a beat hands to the widget: the chat session's public [chatToken] and the messages that were just persisted into it, oldest first. */
data class ProactiveDelivery(
    val chatToken: String,
    val messages: List<DeliveredMessage>,
)

/**
 * Proactive messages from an admin to a visitor who is on a site now (design 2.3). A message waits in memory, in the visitor's
 * [LiveEntry], and is written to the database only when the visitor's next beat takes it ([deliver]): as an `ADMIN` line of the
 * visitor's latest chat session on that site, or of a new `HUMAN` session when there is none. Nothing is stored for a visitor who leaves
 * before the next beat.
 *
 * The write is a human reply by JDBC, so it applies in SQL what `AiChatService.adminReply` does to the session (status `HUMAN`, no
 * unread flag, activity time, ADMIN-8 and ADMIN-14) and what `ConversationMetrics.onHumanReply` stamps (P1a Task 8): without the stamps
 * the missed-conversation job would mark a conversation the admin answered proactively as missed. `flagged`, `lock_reason` and `missed`
 * are never touched (ADMIN-9).
 */
@Service
class ProactiveMessageService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val registry: LiveVisitorRegistry,
    private val geoIp: GeoIpService,
) : ProactiveMessaging {
    internal var clock: Clock = Clock.systemUTC()

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    /** A session found or created for a delivery: its row id and its public token. */
    private class ChatSession(
        val id: Long,
        val token: String,
    )

    /** Trims and caps the text like `adminReply` (BACKEND-20), then queues it for the visitor's latest visit on the site. */
    override fun enqueueProactive(
        visitorKey: String,
        text: String,
        adminUserId: Long,
    ): ProactiveResult {
        val t = text.trim().take(AiChatService.MAX_MESSAGE_CHARS)
        if (t.isBlank()) return ProactiveResult.INVALID_TEXT
        val entry = registry.latestForVisitor(visitorKey)
        return if (entry == null) ProactiveResult.NOT_LIVE else registry.offerPending(entry, PendingProactive(t, adminUserId, clock.instant()))
    }

    /**
     * Writes the messages waiting for [entry] in one transaction and returns them with the session's token, or null when none were
     * waiting. If the write fails nothing is stored, the messages go back to the front of the entry's queue for the next beat, and null
     * is returned: the beat itself must not fail because a delivery did.
     */
    @Suppress("TooGenericExceptionCaught") // any failure (database, transaction, mapping) puts the messages back and the beat carries on
    fun deliver(entry: LiveEntry): ProactiveDelivery? {
        val msgs = registry.drainPending(entry).ifEmpty { return null }
        return try {
            tx.execute { persist(entry, msgs) }
        } catch (e: Exception) {
            registry.requeuePending(entry, msgs)
            log.warn("Proactive delivery to visit {} failed, {} message(s) put back for the next beat: {}", entry.visitKey, msgs.size, e.toString())
            null
        }
    }

    private fun persist(
        entry: LiveEntry,
        msgs: List<PendingProactive>,
    ): ProactiveDelivery {
        val now = clock.instant()
        val at = Timestamp.from(now)
        val session = findSession(entry) ?: createSession(entry, now)
        val delivered =
            msgs.map { m ->
                val id = jdbc.queryForObject(INSERT_MESSAGE_SQL, Long::class.java, session.id, m.text, m.adminUserId, at)!!
                DeliveredMessage(id, m.text, now)
            }
        val wrote = jdbc.queryForObject(VISITOR_WROTE_SQL, Boolean::class.java, session.id) == true
        jdbc.update(HUMAN_REPLY_SQL, at, at, wrote, at, wrote, wrote, at, session.id)
        return ProactiveDelivery(session.token, delivered)
    }

    /** The visitor's latest chat on this site that has not been deleted, whatever its status. */
    private fun findSession(entry: LiveEntry): ChatSession? =
        jdbc
            .query(FIND_SESSION_SQL, SESSION_MAPPER, entry.visitorId, entry.siteId)
            .firstOrNull()

    /** A `HUMAN` session for the visit; it starts flagged and locked when its IP already had [AiChatService.IP_REPEAT_LOCKS] locked chats (BACKEND-6). */
    private fun createSession(
        entry: LiveEntry,
        now: Instant,
    ): ChatSession {
        val at = Timestamp.from(now)
        val repeat =
            entry.ip != null &&
                (
                    jdbc.queryForObject(
                        REPEAT_LOCKS_SQL,
                        Long::class.java,
                        entry.ip,
                        Timestamp.from(now.minus(AiChatService.IP_REPEAT_WINDOW)),
                    ) ?: 0L
                ) >= AiChatService.IP_REPEAT_LOCKS
        val geo = geoIp.countryOf(entry.ip?.takeIf(TrackerClientIp::isIpLiteral)) // a name must not reach a DNS lookup inside the transaction
        return jdbc.queryForObject(
            INSERT_SESSION_SQL,
            SESSION_MAPPER,
            UUID.randomUUID().toString(),
            (entry.locale ?: DEFAULT_LOCALE).take(MAX_LOCALE),
            entry.siteId,
            entry.visitorId,
            entry.visitId,
            entry.ip,
            geo?.code,
            geo?.name,
            repeat,
            if (repeat) REPEAT_LOCK_REASON else null,
            if (repeat) at else null,
            at,
            at,
        )!!
    }

    private companion object {
        val SESSION_MAPPER = RowMapper { rs, _ -> ChatSession(rs.getLong("id"), rs.getString("token")) }

        const val DEFAULT_LOCALE = "en"
        const val MAX_LOCALE = 8 // ai_chat_session.locale is VARCHAR(8)
        const val REPEAT_LOCK_REASON = "IP:REPEAT_OFFENDER:IP_REPEAT"

        const val FIND_SESSION_SQL =
            "SELECT id, token FROM ai_chat_session WHERE visitor_id = ? AND site_id = ? AND deleted_at IS NULL ORDER BY last_activity_at DESC LIMIT 1"

        const val REPEAT_LOCKS_SQL = "SELECT count(*) FROM ai_chat_session WHERE ip = ? AND flagged AND locked_at > ?"

        const val INSERT_SESSION_SQL =
            "INSERT INTO ai_chat_session (token, locale, status, site_id, visitor_id, visit_id, ip, country_code, country, admin_unread, flagged, " +
                "lock_reason, locked_at, created_at, last_activity_at) VALUES (?, ?, 'HUMAN', ?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?, ?) RETURNING id, token"

        const val INSERT_MESSAGE_SQL =
            "INSERT INTO ai_chat_message (session_id, role, content, author_user_id, created_at) VALUES (?, 'ADMIN', ?, ?, ?) RETURNING id"

        const val VISITOR_WROTE_SQL = "SELECT EXISTS (SELECT 1 FROM ai_chat_message WHERE session_id = ? AND role = 'USER')"

        /**
         * `AiChatService.adminReply`'s state change plus `ConversationMetrics.onHumanReply`, as one statement. Every SET expression reads the
         * row as it was before the UPDATE, so `first_response_by` and `first_response_at` agree. Binds: at, at, wrote, at, wrote, wrote, at, id.
         */
        const val HUMAN_REPLY_SQL =
            "UPDATE ai_chat_session SET status = 'HUMAN', admin_unread = false, last_activity_at = ?, " +
                "taken_over_at = COALESCE(taken_over_at, ?), " +
                "first_human_response_at = CASE WHEN ? THEN COALESCE(first_human_response_at, ?) ELSE first_human_response_at END, " +
                "first_response_by = CASE WHEN ? AND first_response_at IS NULL THEN 'HUMAN' ELSE first_response_by END, " +
                "first_response_at = CASE WHEN ? AND first_response_at IS NULL THEN ? ELSE first_response_at END " +
                "WHERE id = ?"
    }
}
