package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.SiteIds
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** What the recordings list asks for; [before] and [beforeId] continue after the last row of the previous page. */
data class RecFilter(
    val from: Instant,
    val to: Instant,
    val siteId: String? = null,
    val device: String? = null,
    val pageType: String? = null,
    val rageOnly: Boolean = false,
    val noInquiry: Boolean = false,
    val visitId: Long? = null,
    val conversationId: Long? = null,
    val before: Instant? = null,
    val beforeId: Long? = null,
    val size: Int = 50,
)

/** One recorded page view in the list; [device] is the visit's, else read from the viewport width. */
data class RecRow(
    val id: Long,
    val siteId: String,
    val url: String?,
    val pageType: String?,
    val startedAt: Instant,
    val durationMs: Int?,
    val viewportW: Int?,
    val viewportH: Int?,
    val device: String,
    val clicks: Int,
    val rageClicks: Int,
    val errors: Int,
    val visitId: Long?,
    val inquiry: Boolean,
    val chat: Boolean,
)

data class RecPage(
    val id: Long,
    val url: String?,
    val pageType: String?,
    val startedAt: Instant,
    val durationMs: Int?,
)

data class RecClick(
    val tMs: Int,
    val x: Int?,
    val y: Int?,
)

data class RecErrorMark(
    val tMs: Int,
    val message: String?,
)

/** A recording opened in the player: the playable pages of its visit in order, and the marks of its timeline. */
data class RecDetail(
    val row: RecRow,
    val visitPages: List<RecPage>,
    val clicks: List<RecClick>,
    val rage: List<Int>,
    val errors: List<RecErrorMark>,
)

/**
 * The recordings list and a recording's details (P6 R2). Only closed recordings whose file still exists are listed, never one
 * of a staff or bot visit. A rage click is 3 or more clicks on the same element within 1000 ms; [RecDetail.rage] holds the
 * time of the first click of each burst.
 */
@Service
class RecQuery(
    private val jdbc: JdbcTemplate,
) {
    fun list(f: RecFilter): List<RecRow> {
        val where = StringBuilder(" AND p.started_at >= ? AND p.started_at < ?")
        val args = mutableListOf<Any>(Timestamp.from(f.from), Timestamp.from(f.to))
        f.siteId?.let {
            where.append(" AND p.site_id = ?")
            args += it
        }
        f.device?.let {
            where.append(" AND $DEVICE = ?")
            args += it
        }
        f.pageType?.let {
            where.append(" AND p.page_type = ?")
            args += it
        }
        if (f.rageOnly) where.append(" AND ($RAGE_COUNT) > 0")
        if (f.noInquiry) where.append(" AND NOT ($INQUIRY)")
        f.visitId?.let {
            where.append(" AND p.visit_id = ?")
            args += it
        }
        f.conversationId?.let {
            where.append(" AND p.visit_id = (SELECT s.visit_id FROM ai_chat_session s WHERE s.id = ?)")
            args += it
        }
        if (f.before != null && f.beforeId != null) {
            where.append(" AND (p.started_at, p.id) < (?, ?)")
            args += Timestamp.from(f.before)
            args += f.beforeId
        }
        return jdbc.query("$SELECT$where ORDER BY p.started_at DESC, p.id DESC LIMIT ${f.size.coerceIn(1, MAX_SIZE)}", ::row, *args.toTypedArray())
    }

    fun detail(id: Long): RecDetail? {
        val row = jdbc.query("$SELECT AND p.id = ?", ::row, id).firstOrNull() ?: return null
        val pages =
            if (row.visitId == null) {
                listOf(RecPage(row.id, row.url, row.pageType, row.startedAt, row.durationMs))
            } else {
                jdbc.query(
                    "SELECT id, url, page_type, started_at, duration_ms FROM rec_page_view p WHERE visit_id = ? AND $PLAYABLE ORDER BY started_at, id",
                    { rs, _ -> RecPage(rs.getLong("id"), rs.getString("url"), rs.getString("page_type"), rs.getTimestamp("started_at").toInstant(), rs.int("duration_ms")) },
                    row.visitId,
                )
            }
        val clicks =
            jdbc.query(
                "SELECT t_ms, x, y FROM rec_click WHERE page_view_id = ? ORDER BY t_ms, id LIMIT $MAX_CLICKS",
                { rs, _ -> RecClick(rs.getInt("t_ms"), rs.int("x"), rs.int("y")) },
                id,
            )
        val rage = jdbc.queryForList("${rageStarts("?")} ORDER BY t_ms", Int::class.java, id)
        val errors =
            jdbc.query(
                "SELECT t_ms, message FROM rec_error WHERE page_view_id = ? ORDER BY t_ms, id LIMIT $MAX_ERRORS",
                { rs, _ -> RecErrorMark(rs.getInt("t_ms"), rs.getString("message")) },
                id,
            )
        return RecDetail(row, pages, clicks, rage, errors)
    }

    /** The file of a playable recording, relative to the recordings folder; null when it is open, purged or unknown. */
    fun filePath(id: Long): String? = jdbc.queryForList("SELECT file_path FROM rec_page_view p WHERE id = ? AND $PLAYABLE", String::class.java, id).firstOrNull()

    /** Who watched which recording: one row per view of a raw recording. */
    fun audit(
        pageViewId: Long,
        userId: Long,
    ) {
        jdbc.update("INSERT INTO rec_view_audit (page_view_id, user_id) VALUES (?, ?)", pageViewId, userId)
    }

    /** The archived copies of [paths] (Next.js stylesheets) on [siteId]'s own site, as `/replay-assets/…` paths; unknown ones are left out. */
    fun assets(
        siteId: String,
        paths: List<String>,
    ): Map<String, String> {
        val host = SiteIds.HOSTS.entries.firstOrNull { it.value == siteId }?.let { "www.${it.key}" } ?: return emptyMap()
        val wanted = paths.distinct().filter(RecAssetArchiver::isStylesheetPath).take(MAX_ASSET_PATHS)
        if (wanted.isEmpty()) return emptyMap()
        val byUrl = wanted.associateBy { "https://$host$it" }
        return jdbc
            .query(
                "SELECT DISTINCT ON (url) url, path FROM rec_asset WHERE url IN (${byUrl.keys.joinToString(",") { "?" }}) ORDER BY url, id DESC",
                { rs, _ -> byUrl.getValue(rs.getString("url")) to "/replay-assets/${rs.getString("path")}" },
                *byUrl.keys.toTypedArray(),
            ).toMap()
    }

    private fun row(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") n: Int,
    ) = RecRow(
        id = rs.getLong("id"),
        siteId = rs.getString("site_id"),
        url = rs.getString("url"),
        pageType = rs.getString("page_type"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        durationMs = rs.int("duration_ms"),
        viewportW = rs.int("viewport_w"),
        viewportH = rs.int("viewport_h"),
        device = rs.getString("device"),
        clicks = rs.getInt("clicks"),
        rageClicks = rs.getInt("rage"),
        errors = rs.getInt("errors"),
        visitId = rs.getLong("visit_id").takeUnless { rs.wasNull() },
        inquiry = rs.getBoolean("inquiry"),
        chat = rs.getBoolean("chat"),
    )

    private fun ResultSet.int(column: String): Int? = getInt(column).takeUnless { wasNull() }

    companion object {
        private const val MAX_SIZE = 100
        private const val MAX_CLICKS = 5000
        private const val MAX_ERRORS = 500
        private const val MAX_ASSET_PATHS = 50
        private const val PLAYABLE = "p.status = 'CLOSED' AND NOT p.file_purged AND p.file_path IS NOT NULL"
        private const val DEVICE =
            "COALESCE(v.device_class, CASE WHEN p.viewport_w IS NULL OR p.viewport_w >= 1024 THEN 'DESKTOP' " +
                "WHEN p.viewport_w < 768 THEN 'MOBILE' ELSE 'TABLET' END)"
        private const val INQUIRY = "p.visit_id IS NOT NULL AND EXISTS (SELECT 1 FROM cc_event ev WHERE ev.visit_id = p.visit_id AND ev.type = 'INQUIRY')"
        private const val CHAT = "p.visit_id IS NOT NULL AND EXISTS (SELECT 1 FROM ai_chat_session s WHERE s.visit_id = p.visit_id)"

        /** The first click of each burst of 3 or more clicks on one element within 1000 ms, of the page view [pageView] (an SQL expression). */
        private fun rageStarts(pageView: String) =
            "SELECT t_ms FROM (SELECT c.t_ms, " +
                "count(*) OVER (PARTITION BY c.hash ORDER BY c.t_ms RANGE BETWEEN CURRENT ROW AND 1000 FOLLOWING) AS n, " +
                "c.t_ms - lag(c.t_ms) OVER (PARTITION BY c.hash ORDER BY c.t_ms) AS gap " +
                "FROM rec_click c WHERE c.page_view_id = $pageView AND c.hash IS NOT NULL) b WHERE b.n >= 3 AND (b.gap IS NULL OR b.gap > 1000)"

        private val RAGE_COUNT = "SELECT count(*) FROM (${rageStarts("p.id")}) r"

        private val SELECT =
            "SELECT p.id, p.site_id, p.url, p.page_type, p.started_at, p.duration_ms, p.viewport_w, p.viewport_h, p.visit_id, " +
                "$DEVICE AS device, (SELECT count(*) FROM rec_click c WHERE c.page_view_id = p.id) AS clicks, ($RAGE_COUNT) AS rage, " +
                "(SELECT count(*) FROM rec_error e WHERE e.page_view_id = p.id) AS errors, ($INQUIRY) AS inquiry, ($CHAT) AS chat " +
                "FROM rec_page_view p LEFT JOIN cc_visit v ON v.id = p.visit_id LEFT JOIN cc_visitor vr ON vr.id = v.visitor_id " +
                "WHERE $PLAYABLE AND (v.id IS NULL OR (NOT v.is_bot AND NOT vr.is_staff))"
    }
}
