package hr.workspace.boat4you.domains.contactcenter.rec

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

/** One element of a heatmap: [pageViews] is how many page views clicked it at least once. */
data class HeatmapElement(
    val hash: String,
    val text: String?,
    val tag: String?,
    val link: String?,
    val clicks: Int,
    val pageViews: Int,
    val rage: Int,
    val dead: Int,
    val inquiryClicks: Int,
)

/** Clicks at one spot of an element; eX/eY are clarity's 0..32767 within the element (the centre of a 1024 grid cell). */
data class HeatPoint(
    val eX: Int,
    val eY: Int,
    val clicks: Int,
)

/** How many page views scrolled down to at least [pct] % of the page. */
data class ScrollReach(
    val pct: Int,
    val pageViews: Int,
)

data class Heatmap(
    val siteId: String,
    val pageType: String,
    val device: String,
    val pageViews: Int,
    val inquiryPageViews: Int,
    val clicks: Int,
    val elements: List<HeatmapElement>,
    val points: Map<String, List<HeatPoint>>,
    val scroll: List<ScrollReach>,
    /** The first screen as % of the page (the average fold). */
    val foldPct: Int,
    /** The page view whose page is the background (the newest one with its file), null when none has a file left. */
    val background: Long?,
)

data class HeatmapGroup(
    val pageType: String,
    val pageViews: Int,
    val clicks: Int,
)

/**
 * One finding for "Što popraviti" (what to fix). [kind]: DEAD (clicked, nothing happened), RAGE (clicked again and again),
 * INQUIRY_ELEMENT (clicked far more by visitors who sent an inquiry), CTA_REACH (how many get as far down as the inquiry or
 * booking button: [percent]), LOW_REACH (half the page views never see the lower half). The admin words them.
 */
data class RecInsight(
    val kind: String,
    val pageType: String,
    val hash: String?,
    val text: String?,
    val tag: String?,
    val count: Int,
    val percent: Int?,
    val pageViews: Int,
)

/**
 * Heatmaps and design insights (P6 R3), per site + page type + device + range: never a staff or bot visit; clicks and scrolls
 * stay 13 months, so a heatmap needs no file except for its background.
 */
@Service
class RecHeatmapQuery(
    private val jdbc: JdbcTemplate,
) {
    fun heatmap(
        siteId: String,
        pageType: String,
        device: String,
        from: Instant,
        to: Instant,
    ): Heatmap {
        val args = arrayOf<Any>(siteId, pageType, device, Timestamp.from(from), Timestamp.from(to))
        val counts =
            jdbc.queryForMap(
                "$PV SELECT count(*) AS n, count(*) FILTER (WHERE inq) AS inq, (SELECT count(*) FROM rec_click c JOIN pv ON pv.id = c.page_view_id) AS clicks FROM pv",
                *args,
            )
        val rage =
            jdbc
                .query(
                    "$PV SELECT h, count(*) FROM (SELECT COALESCE(c.hash_beta, c.hash) AS h, " +
                        "count(*) OVER (PARTITION BY c.page_view_id, c.hash ORDER BY c.t_ms RANGE BETWEEN CURRENT ROW AND 1000 FOLLOWING) AS n, " +
                        "c.t_ms - lag(c.t_ms) OVER (PARTITION BY c.page_view_id, c.hash ORDER BY c.t_ms) AS gap " +
                        "FROM rec_click c JOIN pv ON pv.id = c.page_view_id WHERE c.hash IS NOT NULL) b WHERE b.n >= 3 AND (b.gap IS NULL OR b.gap > 1000) GROUP BY h",
                    { rs, _ -> rs.getString(1) to rs.getInt(2) },
                    *args,
                ).toMap()
        val elements =
            jdbc.query(
                "$PV SELECT COALESCE(c.hash_beta, c.hash) AS h, count(*) AS clicks, count(DISTINCT c.page_view_id) AS pvs, " +
                    "count(*) FILTER (WHERE c.dead) AS dead, count(*) FILTER (WHERE pv.inq) AS inq, " +
                    "mode() WITHIN GROUP (ORDER BY c.text) AS text, mode() WITHIN GROUP (ORDER BY c.tag) AS tag, mode() WITHIN GROUP (ORDER BY c.link) AS link " +
                    "FROM rec_click c JOIN pv ON pv.id = c.page_view_id WHERE COALESCE(c.hash_beta, c.hash) IS NOT NULL " +
                    "GROUP BY 1 ORDER BY clicks DESC, h LIMIT $MAX_ELEMENTS",
                { rs, _ ->
                    val h = rs.getString("h")
                    HeatmapElement(
                        hash = h,
                        text = rs.getString("text"),
                        tag = rs.getString("tag"),
                        link = rs.getString("link"),
                        clicks = rs.getInt("clicks"),
                        pageViews = rs.getInt("pvs"),
                        rage = rage[h] ?: 0,
                        dead = rs.getInt("dead"),
                        inquiryClicks = rs.getInt("inq"),
                    )
                },
                *args,
            )
        val wanted = elements.map { it.hash }.toSet()
        val points =
            jdbc
                .query(
                    "$PV SELECT COALESCE(c.hash_beta, c.hash) AS h, (COALESCE(c.ex, 16383) / $GRID) * $GRID + ${GRID / 2} AS bx, " +
                        "(COALESCE(c.ey, 16383) / $GRID) * $GRID + ${GRID / 2} AS by, " +
                        "count(*) AS n FROM rec_click c JOIN pv ON pv.id = c.page_view_id WHERE COALESCE(c.hash_beta, c.hash) IS NOT NULL GROUP BY 1, 2, 3 ORDER BY n DESC",
                    { rs, _ -> rs.getString("h") to HeatPoint(rs.getInt("bx"), rs.getInt("by"), rs.getInt("n")) },
                    *args,
                ).filter { it.first in wanted }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, v) -> v.take(MAX_POINTS) }
        val reach = reaches(args)
        val background =
            jdbc.queryForList(
                "$PV SELECT p.id FROM pv JOIN rec_page_view p ON p.id = pv.id WHERE NOT p.file_purged AND p.file_path IS NOT NULL ORDER BY p.started_at DESC, p.id DESC LIMIT 1",
                Long::class.java,
                *args,
            ).firstOrNull()
        return Heatmap(
            siteId = siteId,
            pageType = pageType,
            device = device,
            pageViews = (counts["n"] as Number).toInt(),
            inquiryPageViews = (counts["inq"] as Number).toInt(),
            clicks = (counts["clicks"] as Number).toInt(),
            elements = elements,
            points = points,
            scroll = (0..100 step STEP).map { pct -> ScrollReach(pct, reach.reaches.count { it >= pct }) },
            foldPct = reach.fold,
            background = background,
        )
    }

    /** The page types of a site and device in the range, with their page views and clicks, most viewed first. */
    fun groups(
        siteId: String,
        device: String,
        from: Instant,
        to: Instant,
    ): List<HeatmapGroup> =
        jdbc.query(
            "SELECT p.page_type, count(*) AS n, (SELECT count(*) FROM rec_click c WHERE c.page_view_id = ANY(array_agg(p.id))) AS clicks " +
                "$FROM_PV AND p.site_id = ? AND ${RecQuery.DEVICE} = ? AND p.started_at >= ? AND p.started_at < ? AND p.page_type IS NOT NULL " +
                "GROUP BY p.page_type ORDER BY n DESC, p.page_type",
            { rs, _ -> HeatmapGroup(rs.getString(1), rs.getInt(2), rs.getInt(3)) },
            siteId,
            device,
            Timestamp.from(from),
            Timestamp.from(to),
        )

    /**
     * What to fix on [siteId] for [device]: for every page type with at least [minPageViews] page views, dead and rage clicks
     * on an element (at least [minClicks]), elements visitors who inquired click far more, how many reach the inquiry or booking
     * button and whether half the page views ever see the lower half. Strongest first.
     */
    @Suppress("CyclomaticComplexMethod") // one rule per kind of finding, in order
    fun insights(
        siteId: String,
        device: String,
        from: Instant,
        to: Instant,
        minPageViews: Int = MIN_PAGE_VIEWS,
        minClicks: Int = MIN_CLICKS,
    ): List<RecInsight> {
        val out = ArrayList<RecInsight>()
        groups(siteId, device, from, to).filter { it.pageViews >= minPageViews }.forEach { g ->
            val h = heatmap(siteId, g.pageType, device, from, to)
            val others = h.pageViews - h.inquiryPageViews
            h.elements.forEach { e ->
                if (e.dead >= minClicks && e.dead * 2 >= e.clicks) out += insight("DEAD", g, e, e.dead, h.pageViews)
                if (e.rage >= minClicks) out += insight("RAGE", g, e, e.rage, h.pageViews)
                // Clicked by a larger share of the page views of visits that inquired than of the others (at least twice).
                if (h.inquiryPageViews > 0 && e.inquiryClicks >= minClicks) {
                    val inqShare = e.inquiryClicks.toDouble() / h.inquiryPageViews
                    val otherShare = if (others > 0) (e.clicks - e.inquiryClicks).toDouble() / others else 0.0
                    if (inqShare >= 2 * otherShare) out += insight("INQUIRY_ELEMENT", g, e, e.inquiryClicks, h.pageViews)
                }
            }
            val reach = reaches(arrayOf(siteId, g.pageType, device, Timestamp.from(from), Timestamp.from(to)))
            ctaDepth(siteId, g.pageType, device, from, to)?.let { depth ->
                val pct = if (reach.reaches.isEmpty()) 0 else reach.reaches.count { it >= depth } * 100 / reach.reaches.size
                if (pct < CTA_REACH_WARN) out += RecInsight("CTA_REACH", g.pageType, null, null, null, depth, pct, h.pageViews)
            }
            val half = if (reach.reaches.isEmpty()) 100 else reach.reaches.count { it >= HALF } * 100 / reach.reaches.size
            if (half < HALF) out += RecInsight("LOW_REACH", g.pageType, null, null, null, half, half, h.pageViews)
        }
        return out.sortedByDescending { it.count * it.pageViews.coerceAtLeast(1) }
    }

    private fun insight(
        kind: String,
        g: HeatmapGroup,
        e: HeatmapElement,
        count: Int,
        pageViews: Int,
    ) = RecInsight(kind, g.pageType, e.hash, e.text, e.tag, count, if (e.clicks > 0) count * 100 / e.clicks else null, pageViews)

    private class Reach(
        val reaches: List<Int>,
        val fold: Int,
    )

    /** Per page view of the group: how far down it got, in % of the page ((deepest scroll + viewport) / document height). */
    private fun reaches(args: Array<Any>): Reach {
        val rows =
            jdbc.query(
                "$PV SELECT LEAST(100, (COALESCE(ms.max_y, 0) + p.viewport_h) * 100 / p.doc_h) AS reach, LEAST(100, p.viewport_h * 100 / p.doc_h) AS fold " +
                    "FROM pv JOIN rec_page_view p ON p.id = pv.id LEFT JOIN (SELECT s.page_view_id, max(s.y) AS max_y FROM rec_scroll s JOIN pv ON pv.id = s.page_view_id " +
                    "GROUP BY s.page_view_id) ms ON ms.page_view_id = p.id WHERE p.doc_h > 0 AND p.viewport_h > 0",
                { rs, _ -> rs.getInt(1) to rs.getInt(2) },
                *args,
            )
        return Reach(rows.map { it.first }, if (rows.isEmpty()) 0 else rows.map { it.second }.average().toInt())
    }

    /** How deep (% of the page) the inquiry or booking button sits, from where it was clicked; null when nobody clicked one. */
    private fun ctaDepth(
        siteId: String,
        pageType: String,
        device: String,
        from: Instant,
        to: Instant,
    ): Int? {
        val depths =
            jdbc.queryForList(
                "$PV SELECT c.y * 100 / p.doc_h FROM rec_click c JOIN pv ON pv.id = c.page_view_id JOIN rec_page_view p ON p.id = c.page_view_id " +
                    "WHERE p.doc_h > 0 AND c.y IS NOT NULL AND (c.text ~* ? OR c.link ~* ?) ORDER BY 1",
                Int::class.java,
                siteId,
                pageType,
                device,
                Timestamp.from(from),
                Timestamp.from(to),
                CTA,
                CTA_LINK,
            )
        return depths.getOrNull(depths.size / 2)
    }

    companion object {
        private const val MAX_ELEMENTS = 200
        private const val MAX_POINTS = 50
        private const val GRID = 1024
        private const val STEP = 5
        private const val HALF = 50
        private const val CTA_REACH_WARN = 60
        const val MIN_PAGE_VIEWS = 20
        const val MIN_CLICKS = 5

        // The inquiry and booking buttons in the sites' nine languages (text), or a link to a booking or inquiry page.
        private const val CTA =
            "(inquir|enquir|upit|anfrage|richiesta|demande|solicitud|pedido|aanvraag|zapytan|reach out|contact us|kontakt|" +
                "book|reserv|rezerv|buchen|prenot|r[ée]server|boek|send|po[sš]alji|senden|invia|envoyer|enviar|wy[sś]lij|verstuur)"
        private const val CTA_LINK = "(enter-your-details|/payment|/inquiry|/request-a-quote|/wizard|/contact)"

        private const val FROM_PV =
            "FROM rec_page_view p LEFT JOIN cc_visit v ON v.id = p.visit_id LEFT JOIN cc_visitor vr ON vr.id = v.visitor_id " +
                "WHERE p.status = 'CLOSED' AND (v.id IS NULL OR (NOT v.is_bot AND NOT vr.is_staff))"

        /** The group's page views (site, page type, device, range) with whether their visit sent an inquiry. */
        private val PV =
            "WITH pv AS (SELECT p.id, (${RecQuery.INQUIRY}) AS inq $FROM_PV AND p.site_id = ? AND p.page_type = ? AND ${RecQuery.DEVICE} = ? " +
                "AND p.started_at >= ? AND p.started_at < ?)"
    }
}
