package hr.workspace.boat4you.domains.contactcenter.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * The one-off, approximate `cc_search` rows for IRIS searches made before the live recording: one estimated row per
 * stored reply that carries yacht cards (see [IrisCriteriaEstimator]). It walks every session, soft-deleted and flagged
 * ones included (FACTS-4: the conversation is complete either way); whoever reads `cc_search` for statistics leaves
 * the soft-deleted ones out through `chat_session_id -> ai_chat_session.deleted_at` (ADMIN-7).
 *
 * A session is rebuilt in its own transaction (its estimated rows deleted, then inserted again), so a run can be repeated
 * and a failure costs only that session. Replies from the first live search on are not estimated: the live recording owns them.
 */
@Service
class IrisSearchBackfill(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    /** [sessions] scanned and estimated [rows] inserted. */
    data class Result(
        val sessions: Int,
        val rows: Int,
    )

    private class Session(
        val id: Long,
        val siteId: String,
        val flagged: Boolean,
    )

    private class Message(
        val role: String,
        val content: String?,
        val payload: String?,
        val createdAt: LocalDateTime,
        val beforeLive: Boolean,
    )

    private val tx = TransactionTemplate(transactionManager)

    fun runAll(): Result {
        var sessions = 0
        var rows = 0
        var after = 0L
        while (true) {
            val page =
                jdbc.query(
                    "SELECT id, site_id, flagged FROM ai_chat_session WHERE id > ? ORDER BY id LIMIT $PAGE_SIZE",
                    RowMapper { rs, _ -> Session(rs.getLong("id"), rs.getString("site_id"), rs.getBoolean("flagged")) },
                    after,
                )
            if (page.isEmpty()) break
            for (session in page) rows += tx.execute { backfill(session) } ?: 0
            sessions += page.size
            after = page.last().id
        }
        return Result(sessions, rows)
    }

    /** Rebuilds the estimated rows of one session; returns how many it inserted. Runs inside the caller's transaction. */
    private fun backfill(session: Session): Int {
        jdbc.update("DELETE FROM cc_search WHERE chat_session_id = ? AND estimated", session.id)
        val messages =
            jdbc.query(
                """
                SELECT role, content, payload, created_at,
                       created_at < (SELECT coalesce(min(created_at), 'infinity'::timestamp) FROM cc_search WHERE source = 'AI_CHAT' AND NOT estimated) AS before_live
                FROM ai_chat_message WHERE session_id = ? ORDER BY id
                """.trimIndent(),
                RowMapper { rs, _ ->
                    Message(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("payload"),
                        rs.getObject("created_at", LocalDateTime::class.java),
                        rs.getBoolean("before_live"),
                    )
                },
                session.id,
            )
        var visitorText: String? = null
        var inserted = 0
        for (message in messages) {
            when {
                message.role == AiChatMessage.ROLE_USER -> visitorText = message.content
                message.role == AiChatMessage.ROLE_ASSISTANT && message.beforeLive -> {
                    val estimate = IrisCriteriaEstimator.estimate(message.payload, visitorText, objectMapper) ?: continue
                    insert(session, estimate, message.createdAt)
                    inserted++
                }
            }
        }
        return inserted
    }

    private fun insert(
        session: Session,
        c: SearchCriteria,
        createdAt: LocalDateTime,
    ) {
        jdbc.update(
            """
            INSERT INTO cc_search (source, site_id, chat_session_id, country_code, location, did, start_date, end_date, min_persons, min_cabins,
                                   min_price_eur, max_price_eur, vessel_type, equipment, result_count, total_available, searched_area,
                                   flagged, estimated, created_at)
            VALUES ('AI_CHAT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
            """.trimIndent(),
            session.siteId,
            session.id,
            c.countryCode,
            c.location,
            c.did,
            c.startDate,
            c.endDate,
            c.minPersons,
            c.minCabins,
            c.minPriceEur,
            c.maxPriceEur,
            c.vesselType,
            c.equipment.joinToString(",").ifEmpty { null },
            c.resultCount,
            c.totalAvailable,
            c.searchedArea,
            session.flagged,
            createdAt,
        )
    }

    private companion object {
        const val PAGE_SIZE = 200
    }
}
