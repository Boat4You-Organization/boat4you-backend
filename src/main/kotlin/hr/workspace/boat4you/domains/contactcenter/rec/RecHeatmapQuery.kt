package hr.workspace.boat4you.domains.contactcenter.rec

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * One element of a heatmap: [pageViews] is how many page views clicked it at least once, [inquiryPageViews] how many of them led
 * to an inquiry.
 */
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
    val inquiryPageViews: Int,
    /** Milliseconds the mouse rested on it over the group's page views (P6 R4; 0 without mouse data). */
    val hoverMs: Int = 0,
)

/**
 * Clicks at one spot of an element; eX/eY are clarity's 0..32767 within the element (the centre of a 1024 grid cell). Named in
 * JSON as the admin reads them: Jackson would call a Kotlin `eX` "ex" (3.11.2026: the map had no real points for that).
 */
data class HeatPoint(
    @get:JsonProperty("eX") val eX: Int,
    @get:JsonProperty("eY") val eY: Int,
    val clicks: Int,
)

/** Where the mouse rested within an element (P6 R4): a 1024-grid cell centre (0..32767) and the milliseconds there. */
data class HoverPoint(
    @get:JsonProperty("eX") val eX: Int,
    @get:JsonProperty("eY") val eY: Int,
    val ms: Int,
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
    /** Hover cells per element, the [MAX_ELEMENTS] most rested on (desktop page views without a touch; empty before recorder F8). */
    val hover: Map<String, List<HoverPoint>> = emptyMap(),
    /** The page views with mouse data. */
    val hoverPageViews: Int = 0,
)

data class HeatmapGroup(
    val pageType: String,
    val pageViews: Int,
    val clicks: Int,
)

/**
 * One finding for "Što popraviti" (what to fix). [kind]: DEAD (clicked, nothing happened), RAGE (clicked again and again, and at
 * least once without a reaction), INQUIRY_ELEMENT (clicked by a far larger share of the page views that led to an inquiry: [count]
 * of them, [percent] of all those), CTA_REACH (only [percent] % get as far down as the inquiry or booking button at [count] % of
 * the page), LOW_REACH (only [percent] % ever see the lower half). The admin words them.
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

/** The thresholds of [RecHeatmapQuery.insights]. */
data class InsightLimits(
    val minPageViews: Int = RecHeatmapQuery.MIN_PAGE_VIEWS,
    val minClicks: Int = RecHeatmapQuery.MIN_CLICKS,
    val minInquiryPageViews: Int = RecHeatmapQuery.MIN_INQUIRY_PAGE_VIEWS,
)

/**
 * Heatmaps and design insights (P6 R3), per site + page type + device + range: never a staff or bot visit; clicks and scrolls
 * stay 13 months, so a heatmap needs no file except for its background. The reads run on the database the live booking API
 * uses, so each answer is read once in a read-only transaction with a statement timeout and kept [CACHE_MS].
 */
@Service
class RecHeatmapQuery(
    private val jdbc: JdbcTemplate,
) {
    private class Cached(
        val atMs: Long,
        val value: Any,
    )

    private val cache = ConcurrentHashMap<List<Any>, Cached>()

    fun heatmap(
        siteId: String,
        pageType: String,
        device: String,
        from: Instant,
        to: Instant,
    ): Heatmap =
        cached(listOf("heatmap", siteId, pageType, device, from, to)) {
            read { db ->
                val args = args(siteId, pageType, device, from, to)
                val s = stats(db, args, withHover = true)
                val reach = reaches(db, args)
                Heatmap(
                    siteId = siteId,
                    pageType = pageType,
                    device = device,
                    pageViews = s.pageViews,
                    inquiryPageViews = s.inquiryPageViews,
                    clicks = s.clicks,
                    elements = s.elements,
                    points = points(db, args),
                    scroll = (0..100 step STEP).map { pct -> ScrollReach(pct, reach.reaches.count { it >= pct }) },
                    foldPct = reach.fold,
                    hover = hover(db, args),
                    hoverPageViews =
                        db.queryForObject("$PV SELECT count(*) FROM pv WHERE EXISTS (SELECT 1 FROM rec_hover h WHERE h.page_view_id = pv.id)", Int::class.java, *args) ?: 0,
                    background =
                        db
                            .queryForList(
                                "$PV SELECT p.id FROM pv JOIN rec_page_view p ON p.id = pv.id WHERE NOT p.file_purged AND p.file_path IS NOT NULL " +
                                    "ORDER BY p.started_at DESC, p.id DESC LIMIT 1",
                                Long::class.java,
                                *args,
                            ).firstOrNull(),
                )
            }
        }

    /** The page types of a site and device in the range, with their page views and clicks, most viewed first. */
    fun groups(
        siteId: String,
        device: String,
        from: Instant,
        to: Instant,
    ): List<HeatmapGroup> = cached(listOf("groups", siteId, device, from, to)) { read { db -> groups(db, siteId, device, from, to) } }

    /**
     * What to fix on [siteId] for [device]: for every page type with at least [InsightLimits.minPageViews] page views, dead and rage
     * clicks on an element (at least [InsightLimits.minClicks]), elements the page views that led to an inquiry click far more (once
     * a page type has [InsightLimits.minInquiryPageViews] of those), how many reach the inquiry or booking buttons and whether half
     * the page views ever see the lower half. Worst first: the most clicks or page views affected.
     */
    fun insights(
        siteId: String,
        device: String,
        from: Instant,
        to: Instant,
        limits: InsightLimits = InsightLimits(),
    ): List<RecInsight> =
        cached(listOf("insights", siteId, device, from, to, limits)) {
            read { db ->
                val out = ArrayList<RecInsight>()
                groups(db, siteId, device, from, to).filter { it.pageViews >= limits.minPageViews }.forEach { g ->
                    val args = args(siteId, g.pageType, device, from, to)
                    val s = stats(db, args)
                    val reach = reaches(db, args)
                    s.elements.forEach { e ->
                        if (e.dead >= limits.minClicks && e.dead * 2 >= e.clicks) out += e.finding("DEAD", g, e.dead, e.dead * 100 / e.clicks, s)
                        if (e.rage >= limits.minClicks) out += e.finding("RAGE", g, e.rage, e.rage * 100 / e.clicks, s)
                        if (s.inquiryPageViews >= limits.minInquiryPageViews && e.inquiryPageViews >= limits.minClicks && leadsToInquiry(g, s, e)) {
                            out += e.finding("INQUIRY_ELEMENT", g, e.inquiryPageViews, e.inquiryPageViews * 100 / s.inquiryPageViews, s)
                        }
                    }
                    ctas(db, args, limits.minClicks).forEach { cta ->
                        val pct = reach.share(cta.depth)
                        if (pct < CTA_REACH_WARN) out += RecInsight("CTA_REACH", g.pageType, cta.hash, cta.text, cta.tag, cta.depth, pct, s.pageViews)
                    }
                    val half = if (reach.reaches.isEmpty()) 100 else reach.share(HALF)
                    if (half < HALF) out += RecInsight("LOW_REACH", g.pageType, null, null, null, half, half, s.pageViews)
                }
                out.sortedByDescending(::severity)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(
        key: List<Any>,
        load: () -> T,
    ): T {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.atMs < CACHE_MS }?.let { return it.value as T }
        if (cache.size >= CACHE_MAX) {
            cache.entries.removeIf { now - it.value.atMs >= CACHE_MS }
            if (cache.size >= CACHE_MAX) cache.clear()
        }
        return load().also { cache[key] = Cached(now, it) }
    }

    /** [block] on one connection in a read-only transaction whose statements stop after [STATEMENT_TIMEOUT_S]. */
    private fun <T : Any> read(block: (JdbcTemplate) -> T): T =
        jdbc.execute(
            ConnectionCallback<T> { conn ->
                val autoCommit = conn.autoCommit
                val readOnly = conn.isReadOnly
                conn.autoCommit = false
                conn.isReadOnly = true
                var done = false
                try {
                    val db = JdbcTemplate(SingleConnectionDataSource(conn, true))
                    // SET LOCAL: gone at the end of the transaction, so the pooled connection goes back clean.
                    db.execute("SET LOCAL statement_timeout = '${STATEMENT_TIMEOUT_S}s'")
                    block(db).also {
                        conn.commit()
                        done = true
                    }
                } finally {
                    if (!done) conn.rollback()
                    conn.isReadOnly = readOnly
                    conn.autoCommit = autoCommit
                }
            },
        )!!

    private fun args(
        siteId: String,
        pageType: String,
        device: String,
        from: Instant,
        to: Instant,
    ) = arrayOf<Any>(siteId, pageType, device, Timestamp.from(from), Timestamp.from(to))

    private fun groups(
        db: JdbcTemplate,
        siteId: String,
        device: String,
        from: Instant,
        to: Instant,
    ): List<HeatmapGroup> =
        db.query(
            "SELECT p.page_type, count(*) AS n, (SELECT count(*) FROM rec_click c WHERE c.page_view_id = ANY(array_agg(p.id))) AS clicks " +
                "$FROM_PV AND p.site_id = ? AND ${RecQuery.DEVICE} = ? AND p.started_at >= ? AND p.started_at < ? AND p.page_type IS NOT NULL " +
                "GROUP BY p.page_type ORDER BY n DESC, p.page_type",
            { rs, _ -> HeatmapGroup(rs.getString(1), rs.getInt(2), rs.getInt(3)) },
            siteId,
            device,
            Timestamp.from(from),
            Timestamp.from(to),
        )

    private class Stats(
        val pageViews: Int,
        val inquiryPageViews: Int,
        val clicks: Int,
        val elements: List<HeatmapElement>,
    )

    /** The group's counts and its [MAX_ELEMENTS] most clicked elements, with the mouse time per element when [withHover]. */
    private fun stats(
        db: JdbcTemplate,
        args: Array<Any>,
        withHover: Boolean = false,
    ): Stats {
        val counts =
            db.queryForMap(
                "$PV SELECT count(*) AS n, count(*) FILTER (WHERE inq) AS inq, (SELECT count(*) FROM rec_click c JOIN pv ON pv.id = c.page_view_id) AS clicks FROM pv",
                *args,
            )
        // A burst: 3 or more clicks on the same element within 1 s, at least one of them dead (a working gallery arrow is no rage).
        val rage =
            db
                .query(
                    "$PV SELECT h, count(*) FROM (SELECT COALESCE(c.hash_beta, c.hash) AS h, count(*) OVER w AS n, bool_or(c.dead) OVER w AS d, " +
                        "c.t_ms - lag(c.t_ms) OVER (PARTITION BY c.page_view_id, c.hash ORDER BY c.t_ms) AS gap " +
                        "FROM rec_click c JOIN pv ON pv.id = c.page_view_id WHERE c.hash IS NOT NULL " +
                        "WINDOW w AS (PARTITION BY c.page_view_id, c.hash ORDER BY c.t_ms RANGE BETWEEN CURRENT ROW AND 1000 FOLLOWING)) b " +
                        "WHERE b.n >= 3 AND b.d AND (b.gap IS NULL OR b.gap > 1000) GROUP BY h",
                    { rs, _ -> rs.getString(1) to rs.getInt(2) },
                    *args,
                ).toMap()
        val hoverMs =
            if (!withHover) {
                emptyMap()
            } else {
                db
                    .query(
                        "$PV SELECT COALESCE(h.hash_beta, h.hash) AS k, sum(h.ms) FROM rec_hover h JOIN pv ON pv.id = h.page_view_id " +
                            "WHERE COALESCE(h.hash_beta, h.hash) IS NOT NULL GROUP BY 1",
                        { rs, _ -> rs.getString(1) to rs.getLong(2) },
                        *args,
                    ).toMap()
            }
        val elements =
            db.query(
                "$PV SELECT COALESCE(c.hash_beta, c.hash) AS h, count(*) AS clicks, count(DISTINCT c.page_view_id) AS pvs, " +
                    "count(*) FILTER (WHERE c.dead) AS dead, count(*) FILTER (WHERE pv.inq) AS inq, count(DISTINCT c.page_view_id) FILTER (WHERE pv.inq) AS inq_pvs, " +
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
                        inquiryPageViews = rs.getInt("inq_pvs"),
                        hoverMs = (hoverMs[h] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                },
                *args,
            )
        return Stats((counts["n"] as Number).toInt(), (counts["inq"] as Number).toInt(), (counts["clicks"] as Number).toInt(), elements)
    }

    /** The clickmap points of the [MAX_ELEMENTS] most clicked elements, at most [MAX_POINTS] grid cells each, the busiest first. */
    private fun points(
        db: JdbcTemplate,
        args: Array<Any>,
    ): Map<String, List<HeatPoint>> =
        db
            .query(
                "$PV, top AS (SELECT COALESCE(c.hash_beta, c.hash) AS h FROM rec_click c JOIN pv ON pv.id = c.page_view_id " +
                    "WHERE COALESCE(c.hash_beta, c.hash) IS NOT NULL GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT $MAX_ELEMENTS) " +
                    "SELECT h, bx, by, n FROM (SELECT top.h, (COALESCE(c.ex, 16383) / $GRID) * $GRID + ${GRID / 2} AS bx, " +
                    "(COALESCE(c.ey, 16383) / $GRID) * $GRID + ${GRID / 2} AS by, count(*) AS n, " +
                    "row_number() OVER (PARTITION BY top.h ORDER BY count(*) DESC) AS r " +
                    "FROM rec_click c JOIN pv ON pv.id = c.page_view_id JOIN top ON top.h = COALESCE(c.hash_beta, c.hash) GROUP BY 1, 2, 3) q " +
                    "WHERE q.r <= $MAX_POINTS ORDER BY h, n DESC",
                { rs, _ -> rs.getString("h") to HeatPoint(rs.getInt("bx"), rs.getInt("by"), rs.getInt("n")) },
                *args,
            ).groupBy({ it.first }, { it.second })

    /** The hover cells of the [MAX_ELEMENTS] elements the mouse rested on most, at most [HOVER_MAX_POINTS] cells each, the longest first. */
    private fun hover(
        db: JdbcTemplate,
        args: Array<Any>,
    ): Map<String, List<HoverPoint>> =
        db
            .query(
                "$PV, top AS (SELECT COALESCE(h.hash_beta, h.hash) AS k FROM rec_hover h JOIN pv ON pv.id = h.page_view_id " +
                    "WHERE COALESCE(h.hash_beta, h.hash) IS NOT NULL GROUP BY 1 ORDER BY sum(h.ms) DESC, 1 LIMIT $MAX_ELEMENTS) " +
                    "SELECT k, ex, ey, ms FROM (SELECT top.k, h.ex, h.ey, sum(h.ms) AS ms, row_number() OVER (PARTITION BY top.k ORDER BY sum(h.ms) DESC) AS r " +
                    "FROM rec_hover h JOIN pv ON pv.id = h.page_view_id JOIN top ON top.k = COALESCE(h.hash_beta, h.hash) GROUP BY 1, 2, 3) q " +
                    "WHERE q.r <= $HOVER_MAX_POINTS ORDER BY k, ms DESC",
                { rs, _ -> rs.getString("k") to HoverPoint(rs.getInt("ex"), rs.getInt("ey"), rs.getLong("ms").coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) },
                *args,
            ).groupBy({ it.first }, { it.second })

    /**
     * Whether page views that led to an inquiry click [e] at least twice as often as the others. Not a finding: the inquiry and
     * booking buttons and form fields themselves (every inquiry clicks those) and the booking page.
     */
    private fun leadsToInquiry(
        g: HeatmapGroup,
        s: Stats,
        e: HeatmapElement,
    ): Boolean {
        val excluded =
            g.pageType == BOOKING || e.tag?.lowercase() in FORM_TAGS ||
                CTA_TEXT.containsMatchIn(e.text.orEmpty()) || CTA_LINK_RE.containsMatchIn(e.link.orEmpty())
        val others = s.pageViews - s.inquiryPageViews
        val otherShare = if (others > 0) (e.pageViews - e.inquiryPageViews).toDouble() / others else 0.0
        return !excluded && e.inquiryPageViews.toDouble() / s.inquiryPageViews >= 2 * otherShare
    }

    private fun HeatmapElement.finding(
        kind: String,
        g: HeatmapGroup,
        count: Int,
        percent: Int,
        s: Stats,
    ) = RecInsight(kind, g.pageType, hash, text, tag, count, percent, s.pageViews)

    /** How many clicks or page views a finding touches: reach findings by the page views that miss the button or the lower half. */
    private fun severity(i: RecInsight): Long =
        when (i.kind) {
            "CTA_REACH", "LOW_REACH" -> (100L - (i.percent ?: 100)) * i.pageViews / 100
            else -> i.count.toLong()
        }

    private class Reach(
        val reaches: List<Int>,
        val fold: Int,
    ) {
        /** % of the page views that got at least [depth] % down. */
        fun share(depth: Int): Int = if (reaches.isEmpty()) 0 else reaches.count { it >= depth } * 100 / reaches.size
    }

    /** Per page view of the group: how far down it got, in % of the page ((deepest scroll + viewport) / document height). */
    private fun reaches(
        db: JdbcTemplate,
        args: Array<Any>,
    ): Reach {
        val rows =
            db.query(
                "$PV SELECT LEAST(100, (COALESCE(ms.max_y, 0) + p.viewport_h) * 100 / p.doc_h) AS reach, LEAST(100, p.viewport_h * 100 / p.doc_h) AS fold " +
                    "FROM pv JOIN rec_page_view p ON p.id = pv.id LEFT JOIN (SELECT s.page_view_id, max(s.y) AS max_y FROM rec_scroll s JOIN pv ON pv.id = s.page_view_id " +
                    "GROUP BY s.page_view_id) ms ON ms.page_view_id = p.id WHERE p.doc_h > 0 AND p.viewport_h > 0",
                { rs, _ -> rs.getInt(1) to rs.getInt(2) },
                *args,
            )
        return Reach(rows.map { it.first }, if (rows.isEmpty()) 0 else rows.map { it.second }.average().toInt())
    }

    private class Cta(
        val hash: String,
        val text: String?,
        val tag: String?,
        val depth: Int,
    )

    /**
     * The inquiry and booking buttons of the group (at least [minClicks] clicks) that stay in one place on the page, with their depth
     * in % of the page. A button clicked at many depths is fixed or sticky — always on screen — and one in the last tenth is the
     * footer: neither is a reach finding.
     */
    private fun ctas(
        db: JdbcTemplate,
        args: Array<Any>,
        minClicks: Int,
    ): List<Cta> =
        db
            .query(
                "$PV SELECT COALESCE(c.hash_beta, c.hash) AS h, mode() WITHIN GROUP (ORDER BY c.text) AS text, mode() WITHIN GROUP (ORDER BY c.tag) AS tag, " +
                    "percentile_cont(0.5) WITHIN GROUP (ORDER BY c.y * 100.0 / p.doc_h) AS mid, " +
                    "percentile_cont(0.75) WITHIN GROUP (ORDER BY c.y * 100.0 / p.doc_h) - percentile_cont(0.25) WITHIN GROUP (ORDER BY c.y * 100.0 / p.doc_h) AS spread " +
                    "FROM rec_click c JOIN pv ON pv.id = c.page_view_id JOIN rec_page_view p ON p.id = c.page_view_id " +
                    "WHERE p.doc_h > 0 AND c.y IS NOT NULL AND COALESCE(c.hash_beta, c.hash) IS NOT NULL AND (c.text ~* ? OR c.link ~* ?) " +
                    "GROUP BY 1 HAVING count(*) >= ? ORDER BY count(*) DESC, 1 LIMIT $MAX_CTAS",
                { rs, _ -> Triple(rs.getString("h"), rs.getDouble("mid"), rs.getDouble("spread")) to (rs.getString("text") to rs.getString("tag")) },
                *args,
                CTA,
                CTA_LINK,
                minClicks,
            ).filter { (m, _) -> m.third <= CTA_MAX_SPREAD && m.second < CTA_FOOTER }
            .map { (m, label) -> Cta(m.first, label.first, label.second, m.second.roundToInt()) }

    companion object {
        private const val MAX_ELEMENTS = 200
        private const val MAX_POINTS = 50
        private const val HOVER_MAX_POINTS = 120
        private const val MAX_CTAS = 5
        private const val GRID = 1024
        private const val STEP = 5
        private const val HALF = 50
        private const val CTA_REACH_WARN = 60
        private const val CTA_MAX_SPREAD = 10.0
        private const val CTA_FOOTER = 90.0
        private const val STATEMENT_TIMEOUT_S = 15
        private const val CACHE_MS = 10L * 60 * 1000
        private const val CACHE_MAX = 200
        private const val BOOKING = "BOOKING"
        private val FORM_TAGS = setOf("input", "textarea", "select", "option", "label")
        const val MIN_PAGE_VIEWS = 20
        const val MIN_CLICKS = 5
        const val MIN_INQUIRY_PAGE_VIEWS = 10

        // The inquiry and booking buttons in the sites' nine languages, at the start of a word ("Facebook" is none), or a link to a
        // booking or inquiry page.
        private const val CTA_WORDS =
            "inquir|enquir|upit|anfrage|richiesta|demande|solicitud|pedido|aanvraag|zapytan|reach out|contact us|kontakt|" +
                "book|reserv|rezerv|buchen|prenot|r[ée]server|boek|send|po[sš]alji|senden|invia|envoyer|enviar|wy[sś]lij|verstuur"
        private const val CTA = "\\m($CTA_WORDS)"
        private val CTA_TEXT = Regex("\\b($CTA_WORDS)", RegexOption.IGNORE_CASE)
        private const val CTA_LINK = "(enter-your-details|/payment|/inquiry|/request-a-quote|/wizard|/contact)"
        private val CTA_LINK_RE = Regex(CTA_LINK, RegexOption.IGNORE_CASE)

        private const val FROM_PV =
            "FROM rec_page_view p LEFT JOIN cc_visit v ON v.id = p.visit_id LEFT JOIN cc_visitor vr ON vr.id = v.visitor_id " +
                "WHERE p.status = 'CLOSED' AND (v.id IS NULL OR (NOT v.is_bot AND NOT vr.is_staff))"

        /** A page view on the way to an inquiry: its visit sent one afterwards (browsing after the inquiry does not count). */
        private const val INQUIRY_AHEAD =
            "p.visit_id IS NOT NULL AND EXISTS (SELECT 1 FROM cc_event ev WHERE ev.visit_id = p.visit_id AND ev.type = 'INQUIRY' " +
                "AND ev.created_at >= p.started_at)"

        /** The group's page views (site, page type, device, range) with whether they led to an inquiry. */
        private val PV =
            "WITH pv AS (SELECT p.id, ($INQUIRY_AHEAD) AS inq $FROM_PV AND p.site_id = ? AND p.page_type = ? AND ${RecQuery.DEVICE} = ? " +
                "AND p.started_at >= ? AND p.started_at < ?)"
    }
}
