package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Heatmaps and design insights (P6 R3): one group = site + page type + device + range; elements from most to least clicked with
 * the share of page views, rage and dead clicks and the clicks of visits that sent an inquiry; click points per element for the
 * clickmap; how far down the page views reach; the newest playable page view as the background; and the insight list.
 */
class RecHeatmapQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = RecHeatmapQuery(jdbc)
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val range = now.minus(Duration.ofDays(30)) to now

    private fun pv(
        minAgo: Long,
        visitId: Long? = null,
        pageType: String = "BOAT",
        viewportW: Int = 1440,
        viewportH: Int = 800,
        docH: Int = 4000,
        purged: Boolean = false,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO rec_page_view (site_id, project_id, user_id, session_id, page_num, url, page_type, started_at, last_payload_at, status, " +
                "file_path, file_purged, viewport_w, viewport_h, doc_h, visit_id) VALUES ('boat4you', 'boat4you', 'u', ?, 1, 'https://www.boat4you.com/boat/x', ?, ?, ?, " +
                "'CLOSED', 'boat4you/x.ndjson.gz', ?, ?, ?, ?, ?) RETURNING id",
            Long::class.java,
            UUID.randomUUID().toString().take(8),
            pageType,
            Timestamp.from(now.minus(Duration.ofMinutes(minAgo))),
            Timestamp.from(now.minus(Duration.ofMinutes(minAgo - 1))),
            purged,
            viewportW,
            viewportH,
            docH,
            visitId,
        )!!

    private fun click(
        pv: Long,
        t: Int,
        hash: String,
        text: String,
        dead: Boolean = false,
        ex: Int = 16000,
        ey: Int = 16000,
        y: Int = 500,
        tag: String = "button",
    ) = jdbc.update(
        "INSERT INTO rec_click (page_view_id, t_ms, x, y, ex, ey, hash, hash_beta, text, tag, dead) VALUES (?, ?, 10, ?, ?, ?, ?, ?, ?, ?, ?)",
        pv,
        t,
        y,
        ex,
        ey,
        "a$hash",
        hash,
        text,
        tag,
        dead,
    )

    private fun inquiry(
        visit: Long,
        at: Instant,
    ) = jdbc.update("INSERT INTO cc_event (visit_id, type, created_at) VALUES (?, 'INQUIRY', ?)", visit, Timestamp.from(at))

    private fun scroll(
        pv: Long,
        y: Int,
    ) = jdbc.update("INSERT INTO rec_scroll (page_view_id, t_ms, y) VALUES (?, 1000, ?)", pv, y)

    private class World(
        val a: Long,
        val b: Long,
        val c: Long,
    )

    /** Three boat page views on desktop: [a] from a visit that sent an inquiry, [b] and [c] not; one search page view. */
    private fun world(): World {
        val (_, visit) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", now.minus(Duration.ofMinutes(60)))
        inquiry(visit, now)
        val a = pv(60, visitId = visit)
        val b = pv(40)
        val c = pv(20, purged = true)
        // "Reach out" on a and b; the photo (dead, rage on b) on b and c; "Specs" once on c.
        click(a, 1000, "reach", "Reach out", y = 3000)
        click(a, 2000, "reach", "Reach out", y = 3000)
        click(b, 1000, "reach", "Reach out", y = 3000)
        listOf(500, 700, 900).forEach { click(b, it, "photo", "", dead = true, ex = 1000, ey = 2000) }
        click(c, 1000, "photo", "", dead = true, ex = 1000, ey = 2000)
        click(c, 3000, "specs", "Specs")
        click(a, 2500, "photos", "Photos")
        scroll(a, 3200) // reaches 100 %
        scroll(b, 1200) // (1200 + 800) / 4000 = 50 %
        pv(10, pageType = "SEARCH")
        return World(a, b, c)
    }

    @Test
    fun `elements from most to least clicked with page view share, rage, dead and inquiry clicks`() {
        world()
        val h = query.heatmap("boat4you", "BOAT", "DESKTOP", range.first, range.second)
        h.pageViews shouldBe 3
        h.inquiryPageViews shouldBe 1
        h.clicks shouldBe 9
        h.elements.map { it.hash } shouldContainExactly listOf("photo", "reach", "photos", "specs")
        h.elements[0].let {
            it.clicks shouldBe 4
            it.pageViews shouldBe 2
            it.dead shouldBe 4
            it.rage shouldBe 1
            it.inquiryClicks shouldBe 0
        }
        h.elements[1].let {
            it.text shouldBe "Reach out"
            it.clicks shouldBe 3
            it.inquiryClicks shouldBe 2
        }
    }

    @Test
    fun `click points per element, the scroll reach in 5 % steps and the newest playable page view as the background`() {
        val w = world()
        val h = query.heatmap("boat4you", "BOAT", "DESKTOP", range.first, range.second)
        h.points.getValue("photo").single().let {
            it.clicks shouldBe 4
            it.eX shouldBe 512 // 1000 lies in the first cell of the 1024 grid: its centre
        }
        // a reaches 100 %, b 50 %, c never scrolled: 800 / 4000 = 20 %
        h.scroll.first { it.pct == 20 }.pageViews shouldBe 3
        h.scroll.first { it.pct == 50 }.pageViews shouldBe 2
        h.scroll.first { it.pct == 100 }.pageViews shouldBe 1
        h.foldPct shouldBe 20
        h.background shouldBe w.b
    }

    @Test
    fun `groups count page views and clicks per page type`() {
        world()
        query.groups("boat4you", "DESKTOP", range.first, range.second).map { Triple(it.pageType, it.pageViews, it.clicks) } shouldContainExactly
            listOf(Triple("BOAT", 3, 9), Triple("SEARCH", 1, 0))
    }

    @Test
    fun `insights - dead and rage clicks, an element inquiring visitors click, the reach of the inquiry button`() {
        world()
        val insights = query.insights("boat4you", "DESKTOP", range.first, range.second, InsightLimits(1, 1, 1))
        insights.map { it.kind to it.hash }.toSet().let {
            it.contains("DEAD" to "photo") shouldBe true
            it.contains("RAGE" to "photo") shouldBe true
            it.contains("INQUIRY_ELEMENT" to "photos") shouldBe true
            // The inquiry button itself is what every inquiry clicks: not a finding.
            it.contains("INQUIRY_ELEMENT" to "reach") shouldBe false
        }
        insights.single { it.kind == "CTA_REACH" }.let {
            it.pageType shouldBe "BOAT"
            it.hash shouldBe "reach"
            it.count shouldBe 75 // the inquiry button sits at 75 % of the page (y 3000 / 4000)
            it.percent shouldBe 33 // 1 of 3 page views get there
        }
    }

    @Test
    fun `a button clicked at many depths is fixed or sticky - always on screen, so its reach is no finding`() {
        listOf(800, 2000, 3200).forEach { y -> click(pv(30, pageType = "HOME"), 1000, "bar", "Send inquiry", y = y) }
        query.insights("boat4you", "DESKTOP", range.first, range.second, InsightLimits(1, 1)).none { it.kind == "CTA_REACH" } shouldBe true
    }

    @Test
    fun `three quick clicks the page reacted to are no rage click - a gallery arrow`() {
        val p = pv(30)
        listOf(100, 400, 700).forEach { click(p, it, "arrow", "") }
        query.heatmap("boat4you", "BOAT", "DESKTOP", range.first, range.second).elements.single().rage shouldBe 0
    }

    @Test
    fun `a page view after the visit's inquiry does not count as inquiring`() {
        val (_, visit) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", now.minus(Duration.ofMinutes(60)))
        inquiry(visit, now.minus(Duration.ofMinutes(30)))
        pv(40, visitId = visit)
        pv(20, visitId = visit)
        query.heatmap("boat4you", "BOAT", "DESKTOP", range.first, range.second).inquiryPageViews shouldBe 1
    }

    @Test
    fun `insights put the worst first - a page few get to the bottom of before a milder one`() {
        // 10 page views each: on HOME 1 gets to the lower half (10 %), on SEARCH 4 do (40 %).
        repeat(10) { i -> pv(30, pageType = "HOME").also { if (i < 1) scroll(it, 3200) } }
        repeat(10) { i -> pv(30, pageType = "SEARCH").also { if (i < 4) scroll(it, 3200) } }
        query.insights("boat4you", "DESKTOP", range.first, range.second, InsightLimits(1, 1)).filter { it.kind == "LOW_REACH" }.map { it.pageType } shouldContainExactly
            listOf("HOME", "SEARCH")
    }

    @Test
    fun `a heatmap asked again within minutes comes from the cache`() {
        val w = world()
        query.heatmap("boat4you", "BOAT", "DESKTOP", range.first, range.second).clicks shouldBe 9
        click(w.b, 5000, "specs", "Specs")
        query.heatmap("boat4you", "BOAT", "DESKTOP", range.first, range.second).clicks shouldBe 9
    }
}
