package hr.workspace.boat4you.domains.contactcenter.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * The contact-center retention (design §8; ADMIN-5, BACKEND-30, BACKEND-32):
 *
 *  - raw tracking (`cc_event`, `cc_page_view` and the `SITE_SEARCH` rows of `cc_search`) goes after [RAW_MONTHS] months;
 *  - conversations (`ai_chat_session`, its messages by cascade), `cc_visit`, `cc_visitor` and `cc_contact` go after
 *    [ACTIVITY_MONTHS] months since the last activity;
 *  - the full IP of a visit and of a chat session becomes NULL after `cc.retention.ip.days` (90 by default); the country stays.
 *
 * Flagged sessions are evidence: they are never purged or IP-cleared, and the visits, visitors and contacts they point at stay
 * with them. `inquiry` is not touched at all (`InquiryRetentionJob` owns it).
 *
 * Every step is a batched statement that auto-commits (no surrounding transaction), the rule of `RetentionReaperJob`: a
 * statement handles at most [batch] rows and repeats until it finds fewer, with [MAX_BATCHES] as the ceiling of one run, so a
 * large backlog drains across nights. The steps run in the order that the foreign keys need: a visit goes before its visitor
 * (`cc_visit.visitor_id` is `ON DELETE CASCADE`, so a visitor whose `last_seen_at` lags would drag recent visits and the
 * visits that evidence points at down with it), and a contact goes last, once no session and no visitor points at it.
 */
@Service
class ContactCenterRetention(
    private val jdbc: JdbcTemplate,
    private val settings: CcSettings,
    @Value("\${cc.retention.batch:5000}") private val batch: Int,
) {
    data class Result(
        val pageViews: Int,
        val events: Int,
        val chatSessions: Int,
        val visits: Int,
        val visitors: Int,
        val contacts: Int,
        val ipsCleared: Int,
        val chatIpsCleared: Int,
        val searches: Int,
    )

    fun purge(): Result {
        val events = deleteOld("cc_event", "x.created_at < now() - make_interval(months => $RAW_MONTHS)")
        val pageViews = deleteOld("cc_page_view", "x.viewed_at < now() - make_interval(months => $RAW_MONTHS)")
        val searches = deleteOld("cc_search", "x.created_at < now() - make_interval(months => $RAW_MONTHS) AND x.source = 'SITE_SEARCH'")
        val chatSessions =
            deleteOld("ai_chat_session", "x.last_activity_at < now() - make_interval(months => $ACTIVITY_MONTHS) AND NOT x.flagged")
        val visits =
            deleteOld(
                "cc_visit",
                "x.last_seen_at < now() - make_interval(months => $ACTIVITY_MONTHS) " +
                    "AND NOT EXISTS (SELECT 1 FROM ai_chat_session s WHERE s.visit_id = x.id AND s.flagged)",
            )
        val visitors =
            deleteOld(
                "cc_visitor",
                "x.last_seen_at < now() - make_interval(months => $ACTIVITY_MONTHS) " +
                    "AND NOT EXISTS (SELECT 1 FROM cc_visit v WHERE v.visitor_id = x.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM ai_chat_session s WHERE s.visitor_id = x.id AND s.flagged)",
            )
        val contacts =
            deleteOld(
                "cc_contact",
                "x.updated_at < now() - make_interval(months => $ACTIVITY_MONTHS) " +
                    "AND NOT EXISTS (SELECT 1 FROM ai_chat_session s WHERE s.contact_id = x.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM cc_visitor v WHERE v.contact_id = x.id)",
            )
        // One value for both IP steps, so a visit and a chat session of one run use the same limit.
        val ipDays = settings.retentionIpDays
        val ipsCleared =
            inBatches(
                "UPDATE cc_visit SET ip = NULL WHERE id IN (SELECT id FROM cc_visit x WHERE x.ip IS NOT NULL " +
                    "AND x.started_at < now() - make_interval(days => ?) " +
                    "AND NOT EXISTS (SELECT 1 FROM ai_chat_session s WHERE s.visit_id = x.id AND s.flagged) LIMIT ?)",
                ipDays,
            )
        val chatIpsCleared =
            inBatches(
                "UPDATE ai_chat_session SET ip = NULL WHERE id IN (SELECT id FROM ai_chat_session x WHERE x.ip IS NOT NULL " +
                    "AND NOT x.flagged AND x.created_at < now() - make_interval(days => ?) LIMIT ?)",
                ipDays,
            )
        return Result(pageViews, events, chatSessions, visits, visitors, contacts, ipsCleared, chatIpsCleared, searches)
    }

    /** `table` and `condition` are constants of this class, never input. */
    private fun deleteOld(
        table: String,
        condition: String,
    ) = inBatches("DELETE FROM $table WHERE id IN (SELECT id FROM $table x WHERE $condition LIMIT ?)")

    /** Runs [sql] with [args] and the batch size as the last argument until a run changes fewer rows than [batch]. Returns the total. */
    private fun inBatches(
        sql: String,
        vararg args: Any,
    ): Int {
        var total = 0
        repeat(MAX_BATCHES) {
            val changed = jdbc.update(sql, *args, batch)
            total += changed
            if (changed < batch) return total
        }
        return total
    }

    companion object {
        const val RAW_MONTHS = 13
        const val ACTIVITY_MONTHS = 24
        const val MAX_BATCHES = 200
    }
}
