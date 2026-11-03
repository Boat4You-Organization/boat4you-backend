package hr.workspace.boat4you.domains.contactcenter.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

/** A last page of visits without an inquiry: [exits] of the [views] visits that saw [path] on [siteId] left there. */
data class ExitPage(
    val siteId: String,
    val path: String,
    val pageType: String,
    val views: Long,
    val exits: Long,
)

/**
 * Clicks on [label] (or its link when it has no text) on pages of [pageType]; OTHER when the page is unknown. [kind] is link or
 * button, [area] where on the page the element sits (gallery, slider, nav, header, footer, form, dialog, main — null for a click
 * the widget sent before P7a), [pages] how many distinct pages (site and path) had it, [siteId] and [path] the page with most
 * of them and [target] its most common link target (null for a button).
 */
data class TopClick(
    val pageType: String,
    val label: String,
    val clicks: Long,
    val pages: Long,
    val kind: String,
    val area: String?,
    val siteId: String?,
    val path: String?,
    val target: String?,
)

/**
 * [steps] are VISIT, BOAT (saw a boat page), FORM (a booking or inquiry page) and INQUIRY (the site reported a sent
 * inquiry). A visit counts at its deepest step and at every step before it, so an inquiry sent from a modal (sister
 * sites) or from a contact form still counts as BOAT and FORM.
 */
data class CcFunnel(
    val from: Instant,
    val to: Instant,
    val siteId: String?,
    val device: String?,
    val channel: String?,
    val steps: List<Count>,
    val chatOpened: Long,
    val exitsByType: List<Count>,
    val exits: List<ExitPage>,
    val clicks: List<TopClick>,
)

/** Inquiry funnel, exit pages and top clicks of a range (P6 R0); bots and staff are never counted. */
@Service
class FunnelQuery(
    private val jdbc: JdbcTemplate,
) {
    fun funnel(
        from: Instant,
        to: Instant,
        siteId: String?,
        device: String?,
        channel: String?,
    ): CcFunnel {
        val filters = listOfNotNull(siteId?.let { "v.site_id" to it }, device?.let { "v.device_class" to it }, channel?.let { "v.channel" to it })
        val visits =
            "WITH fv AS (SELECT v.id, v.site_id FROM cc_visit v JOIN cc_visitor vr ON vr.id = v.visitor_id WHERE v.started_at >= ? AND v.started_at < ?" +
                filters.joinToString("") { " AND ${it.first} = ?" } + " AND NOT v.is_bot AND NOT vr.is_staff)"
        val args = arrayOf<Any>(Timestamp.from(from), Timestamp.from(to), *filters.map { it.second }.toTypedArray())
        val (steps, chatOpened) = steps(visits, args)
        val last =
            "$visits, last AS (SELECT DISTINCT ON (p.visit_id) p.visit_id, fv.site_id, p.path, p.page_type FROM fv JOIN cc_page_view p ON p.visit_id = fv.id " +
                "WHERE NOT $INQUIRED ORDER BY p.visit_id, p.viewed_at DESC, p.id DESC)"
        return CcFunnel(
            from = from,
            to = to,
            siteId = siteId,
            device = device,
            channel = channel,
            steps = steps,
            chatOpened = chatOpened,
            exitsByType =
                jdbc
                    .query("$last SELECT page_type AS k, count(*) AS n FROM last GROUP BY 1", { rs, _ -> Count(rs.getString("k"), rs.getLong("n")) }, *args)
                    .sortedWith(compareByDescending<Count> { it.value }.thenBy { it.key }),
            exits = exits(last, args),
            clicks = clicks(visits, args),
        )
    }

    private fun steps(
        visits: String,
        args: Array<Any>,
    ): Pair<List<Count>, Long> =
        jdbc.queryForObject(
            "$visits, d AS (SELECT CASE WHEN $INQUIRED THEN 4 " +
                "WHEN EXISTS (SELECT 1 FROM cc_page_view p WHERE p.visit_id = fv.id AND p.page_type = 'BOOKING') THEN 3 " +
                "WHEN EXISTS (SELECT 1 FROM cc_page_view p WHERE p.visit_id = fv.id AND p.page_type = 'BOAT') THEN 2 ELSE 1 END AS depth, " +
                "EXISTS (SELECT 1 FROM cc_event e WHERE e.visit_id = fv.id AND e.type = 'CHAT_OPEN') AS chat FROM fv) " +
                "SELECT count(*) AS s1, count(*) FILTER (WHERE depth >= 2) AS s2, count(*) FILTER (WHERE depth >= 3) AS s3, " +
                "count(*) FILTER (WHERE depth >= 4) AS s4, count(*) FILTER (WHERE chat) AS chat FROM d",
            { rs, _ -> STEPS.mapIndexed { i, step -> Count(step, rs.getLong("s${i + 1}")) } to rs.getLong("chat") },
            *args,
        )!!

    private fun exits(
        last: String,
        args: Array<Any>,
    ): List<ExitPage> =
        jdbc
            .query(
                "$last, ex AS (SELECT site_id, path, min(page_type) AS page_type, count(*) AS exits FROM last GROUP BY 1, 2 " +
                    "ORDER BY 4 DESC, 2, 1 LIMIT $TOP_EXITS), " +
                    "pv AS (SELECT fv.site_id, p.path, count(DISTINCT p.visit_id) AS views FROM fv JOIN cc_page_view p ON p.visit_id = fv.id " +
                    "WHERE (fv.site_id, p.path) IN (SELECT site_id, path FROM ex) GROUP BY 1, 2) " +
                    "SELECT ex.site_id, ex.path, ex.page_type, pv.views, ex.exits FROM ex JOIN pv ON pv.site_id = ex.site_id AND pv.path = ex.path",
                { rs, _ -> ExitPage(rs.getString("site_id"), rs.getString("path"), rs.getString("page_type"), rs.getLong("views"), rs.getLong("exits")) },
                *args,
            ).sortedWith(compareByDescending<ExitPage> { it.exits }.thenByDescending { it.views }.thenBy { it.path }.thenBy { it.siteId })

    private fun clicks(
        visits: String,
        args: Array<Any>,
    ): List<TopClick> =
        jdbc.query(
            // c: every click with its kind (the widget's, else link when it has a target), area ('' when unknown; only the known
            // names, the payload is the visitor's browser's), site, page and target; g: the top groups; tp / tt: the most clicked
            // page and the most common target of each top group.
            "$visits, c AS (SELECT COALESCE(p.page_type, 'OTHER') AS t, COALESCE(e.label, e.target) AS l, " +
                "CASE WHEN e.payload->>'kind' IN ('link', 'button') THEN e.payload->>'kind' " +
                "WHEN COALESCE(e.target, '') <> '' THEN 'link' ELSE 'button' END AS k, " +
                "CASE WHEN e.payload->>'area' IN ($AREAS) THEN e.payload->>'area' ELSE '' END AS a, " +
                "fv.site_id AS s, p.path, NULLIF(e.target, '') AS target " +
                "FROM fv JOIN cc_event e ON e.visit_id = fv.id LEFT JOIN cc_page_view p ON p.id = e.page_view_id " +
                "WHERE e.type = 'CLICK' AND COALESCE(e.label, e.target) IS NOT NULL), " +
                "g AS (SELECT t, l, k, a, count(*) AS n, count(DISTINCT (s, path)) FILTER (WHERE path IS NOT NULL) AS pages " +
                "FROM c GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC, 1, 2, 3, 4 LIMIT $TOP_CLICKS), " +
                "tp AS (SELECT DISTINCT ON (t, l, k, a) t, l, k, a, s, path FROM (SELECT t, l, k, a, s, path, count(*) AS n FROM c " +
                "JOIN g USING (t, l, k, a) WHERE path IS NOT NULL GROUP BY 1, 2, 3, 4, 5, 6) x ORDER BY t, l, k, a, n DESC, s, path), " +
                "tt AS (SELECT DISTINCT ON (t, l, k, a) t, l, k, a, target FROM (SELECT t, l, k, a, target, count(*) AS n FROM c " +
                "JOIN g USING (t, l, k, a) WHERE target IS NOT NULL GROUP BY 1, 2, 3, 4, 5) x ORDER BY t, l, k, a, n DESC, target) " +
                "SELECT g.t, g.l, g.k, g.a, g.n, g.pages, tp.s, tp.path, tt.target FROM g " +
                "LEFT JOIN tp USING (t, l, k, a) LEFT JOIN tt USING (t, l, k, a) ORDER BY g.n DESC, g.t, g.l, g.k, g.a",
            { rs, _ ->
                TopClick(
                    pageType = rs.getString("t"),
                    label = rs.getString("l"),
                    clicks = rs.getLong("n"),
                    pages = rs.getLong("pages"),
                    kind = rs.getString("k"),
                    area = rs.getString("a").ifEmpty { null },
                    siteId = rs.getString("s"),
                    path = rs.getString("path"),
                    target = rs.getString("target"),
                )
            },
            *args,
        )

    companion object {
        private const val TOP_EXITS = 20
        private const val TOP_CLICKS = 30
        private const val AREAS = "'gallery', 'slider', 'nav', 'header', 'footer', 'form', 'dialog', 'main'"
        private const val INQUIRED = "EXISTS (SELECT 1 FROM cc_event e WHERE e.visit_id = fv.id AND e.type = 'INQUIRY')"
        private val STEPS = listOf("VISIT", "BOAT", "FORM", "INQUIRY")
    }
}
