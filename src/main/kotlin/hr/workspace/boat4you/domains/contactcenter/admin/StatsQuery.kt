package hr.workspace.boat4you.domains.contactcenter.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

data class Count(
    val key: String,
    val value: Long,
)

/** Conversations started in the range; [aiOnly] never had a broker, [takenOver] did (a takeover stamp or any broker message). */
data class ChatStats(
    val started: Long,
    val startedByTeam: Long,
    val aiOnly: Long,
    val takenOver: Long,
    val missed: Long,
    val medianFirstResponseSeconds: Long?,
    val contactsCollected: Long,
)

data class VisitorStats(
    val visits: Long,
    val visitors: Long,
    val newVisitors: Long,
    val returningVisitors: Long,
    val bySite: List<Count>,
    val byChannel: List<Count>,
    val byCountry: List<Count>,
    val sisterToBoat4you: List<Count>,
)

/** [byMonth] is the month people want to sail (`start_date`), [personsBuckets] always has its four buckets. */
data class SearchStats(
    val total: Long,
    val topLocations: List<Count>,
    val topCountries: List<Count>,
    val byMonth: List<Count>,
    val personsBuckets: List<Count>,
)

data class CcStats(
    val from: Instant,
    val to: Instant,
    val siteId: String?,
    val chat: ChatStats,
    val visitors: VisitorStats,
    val searches: SearchStats,
)

/**
 * Basic Contact Center statistics for a range (P2). Spam contacts, flagged and deleted conversations, bots, staff, and
 * estimated or flagged searches are never counted. A conversation counts when it has at least one message.
 */
@Service
class StatsQuery(
    private val jdbc: JdbcTemplate,
) {
    fun stats(
        from: Instant,
        to: Instant,
        siteId: String?,
    ): CcStats {
        val range = Range(Timestamp.from(from), Timestamp.from(to), siteId)
        return CcStats(from, to, siteId, chat(range), visitors(range), searches(range))
    }

    private data class Range(
        val from: Timestamp,
        val to: Timestamp,
        val siteId: String?,
    ) {
        fun args(): Array<Any> = listOfNotNull<Any>(from, to, siteId).toTypedArray()

        fun site(column: String): String = if (siteId == null) "" else " AND $column = ?"
    }

    private fun chat(r: Range): ChatStats =
        jdbc.queryForObject(
            "SELECT count(*) AS started, count(*) FILTER (WHERE x.first_role = 'ADMIN') AS by_team, " +
                "count(*) FILTER (WHERE NOT x.taken) AS ai_only, count(*) FILTER (WHERE x.taken) AS taken, " +
                "count(*) FILTER (WHERE x.missed) AS missed, " +
                "percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM x.first_response_at - x.created_at)) " +
                "FILTER (WHERE x.first_response_at IS NOT NULL) AS median, " +
                "count(DISTINCT x.contact_id) FILTER (WHERE x.has_details) AS contacts " +
                "FROM (SELECT s.created_at, s.first_response_at, s.missed, s.contact_id, " +
                "(c.email IS NOT NULL OR c.phone IS NOT NULL) AS has_details, " +
                "(SELECT m.role FROM ai_chat_message m WHERE m.session_id = s.id ORDER BY m.id LIMIT 1) AS first_role, " +
                "(s.taken_over_at IS NOT NULL OR EXISTS (SELECT 1 FROM ai_chat_message a WHERE a.session_id = s.id AND a.role = 'ADMIN')) AS taken " +
                "FROM ai_chat_session s LEFT JOIN cc_contact c ON c.id = s.contact_id " +
                "WHERE s.created_at >= ? AND s.created_at < ?${r.site("s.site_id")} AND s.deleted_at IS NULL AND NOT s.flagged " +
                "AND (c.status IS NULL OR c.status <> 'SPAM') AND EXISTS (SELECT 1 FROM ai_chat_message e WHERE e.session_id = s.id)) x",
            { rs, _ ->
                ChatStats(
                    started = rs.getLong("started"),
                    startedByTeam = rs.getLong("by_team"),
                    aiOnly = rs.getLong("ai_only"),
                    takenOver = rs.getLong("taken"),
                    missed = rs.getLong("missed"),
                    medianFirstResponseSeconds = rs.getDouble("median").takeUnless { rs.wasNull() }?.toLong(),
                    contactsCollected = rs.getLong("contacts"),
                )
            },
            *r.args(),
        )!!

    private fun visitors(r: Range): VisitorStats {
        val base =
            "FROM cc_visit v JOIN cc_visitor vr ON vr.id = v.visitor_id WHERE v.started_at >= ? AND v.started_at < ?${r.site("v.site_id")} " +
                "AND NOT v.is_bot AND NOT vr.is_staff"
        val totals =
            jdbc.queryForObject(
                "SELECT count(*) AS visits, count(DISTINCT v.visitor_id) AS visitors $base",
                { rs, _ -> rs.getLong("visits") to rs.getLong("visitors") },
                *r.args(),
            )!!
        val (newVisitors, returning) =
            jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE f.first_at >= ?) AS fresh, count(*) FILTER (WHERE f.first_at < ?) AS returning " +
                    "FROM (SELECT DISTINCT v.visitor_id $base) x JOIN LATERAL (SELECT min(a.started_at) AS first_at FROM cc_visit a " +
                    "WHERE a.visitor_id = x.visitor_id AND NOT a.is_bot) f ON TRUE",
                { rs, _ -> rs.getLong("fresh") to rs.getLong("returning") },
                r.from,
                r.from,
                *r.args(),
            )!!
        val sister =
            if (r.siteId == null) {
                counts(
                    "SELECT v.from_site_id AS k, count(*) AS n $base AND v.site_id = 'boat4you' AND v.from_site_id IS NOT NULL GROUP BY 1",
                    r.args(),
                )
            } else {
                counts(
                    "SELECT v.from_site_id AS k, count(*) AS n FROM cc_visit v JOIN cc_visitor vr ON vr.id = v.visitor_id " +
                        "WHERE v.started_at >= ? AND v.started_at < ? AND v.site_id = 'boat4you' AND v.from_site_id = ? " +
                        "AND NOT v.is_bot AND NOT vr.is_staff GROUP BY 1",
                    r.args(),
                )
            }
        return VisitorStats(
            visits = totals.first,
            visitors = totals.second,
            newVisitors = newVisitors,
            returningVisitors = returning,
            bySite = counts("SELECT v.site_id AS k, count(*) AS n $base GROUP BY 1", r.args()),
            byChannel = counts("SELECT v.channel AS k, count(*) AS n $base GROUP BY 1", r.args()),
            byCountry = counts("SELECT COALESCE(v.country_code, '--') AS k, count(*) AS n $base GROUP BY 1", r.args(), TOP_COUNTRIES),
            sisterToBoat4you = sister,
        )
    }

    private fun searches(r: Range): SearchStats {
        val base =
            "FROM cc_search cs WHERE cs.created_at >= ? AND cs.created_at < ?${r.site("cs.site_id")} AND NOT cs.estimated AND NOT cs.flagged " +
                "AND NOT EXISTS (SELECT 1 FROM ai_chat_session s LEFT JOIN cc_contact c ON c.id = s.contact_id WHERE s.id = cs.chat_session_id " +
                "AND (s.flagged OR s.deleted_at IS NOT NULL OR c.status = 'SPAM'))"
        val buckets =
            counts(
                "SELECT CASE WHEN cs.min_persons <= 4 THEN '1-4' WHEN cs.min_persons <= 8 THEN '5-8' WHEN cs.min_persons <= 12 THEN '9-12' " +
                    "ELSE '13+' END AS k, count(*) AS n $base AND cs.min_persons >= 1 GROUP BY 1",
                r.args(),
            ).associate { it.key to it.value }
        return SearchStats(
            total = jdbc.queryForObject("SELECT count(*) $base", Long::class.java, *r.args())!!,
            topLocations = counts("SELECT cs.location AS k, count(*) AS n $base AND cs.location IS NOT NULL GROUP BY 1", r.args(), TOP),
            topCountries = counts("SELECT cs.country_code AS k, count(*) AS n $base AND cs.country_code IS NOT NULL GROUP BY 1", r.args(), TOP),
            byMonth =
                counts("SELECT to_char(cs.start_date, 'YYYY-MM') AS k, count(*) AS n $base AND cs.start_date IS NOT NULL GROUP BY 1", r.args())
                    .sortedBy { it.key },
            personsBuckets = PERSONS_BUCKETS.map { Count(it, buckets[it] ?: 0) },
        )
    }

    /** `k` / `n` rows, biggest first (ties by key), at most [limit]. */
    private fun counts(
        sql: String,
        args: Array<Any>,
        limit: Int = Int.MAX_VALUE,
    ): List<Count> =
        jdbc
            .query(sql, { rs, _ -> Count(rs.getString("k"), rs.getLong("n")) }, *args)
            .sortedWith(compareByDescending<Count> { it.value }.thenBy { it.key })
            .take(limit)

    companion object {
        private const val TOP = 10
        private const val TOP_COUNTRIES = 20
        private val PERSONS_BUCKETS = listOf("1-4", "5-8", "9-12", "13+")
    }
}
