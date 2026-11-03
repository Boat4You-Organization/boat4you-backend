package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The recordings list and a recording's details in the admin (P6 R2): only closed recordings whose file still exists, never a
 * staff or bot visit, newest first, with device, clicks, rage clicks, errors and whether the visit sent an inquiry or chatted.
 */
class RecQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = RecQuery(jdbc)
    private val now = Instant.parse("2026-11-03T12:00:00Z")

    private fun pv(
        site: String,
        startedMinAgo: Long,
        visitId: Long? = null,
        status: String = "CLOSED",
        purged: Boolean = false,
        viewportW: Int = 1440,
        url: String = "https://www.boat4you.com/boat/x",
        pageType: String = "BOAT",
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO rec_page_view (site_id, project_id, user_id, session_id, page_num, url, page_type, started_at, last_payload_at, " +
                "status, file_path, file_purged, viewport_w, viewport_h, duration_ms, visit_id) VALUES (?, ?, 'u', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 800, 60000, ?) RETURNING id",
            Long::class.java,
            site,
            site,
            UUID.randomUUID().toString().take(8),
            url,
            pageType,
            Timestamp.from(now.minus(Duration.ofMinutes(startedMinAgo))),
            Timestamp.from(now.minus(Duration.ofMinutes(startedMinAgo - 1))),
            status,
            "$site/2026-11-03/x-$startedMinAgo.ndjson.gz",
            purged,
            viewportW,
            visitId,
        )!!

    private fun click(
        pageView: Long,
        tMs: Int,
        hash: String,
    ) = jdbc.update("INSERT INTO rec_click (page_view_id, t_ms, x, y, hash) VALUES (?, ?, 10, 20, ?)", pageView, tMs, hash)

    private fun all(f: RecFilter = RecFilter(from = now.minus(Duration.ofDays(7)), to = now)) = query.list(f)

    private class World(
        val desktop: Long,
        val mobile: Long,
        val visitId: Long,
        val chatId: Long,
        val secondPage: Long,
    )

    private fun world(): World {
        val (_, visitId) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", now.minus(Duration.ofMinutes(30)))
        jdbc.update("INSERT INTO cc_event (visit_id, type) VALUES (?, 'INQUIRY')", visitId)
        val chatId = jdbc.queryForObject("INSERT INTO ai_chat_session (token, visit_id) VALUES ('t-rec', ?) RETURNING id", Long::class.java, visitId)!!
        val desktop = pv("boat4you", 30, visitId = visitId)
        listOf(100 to "a", 400 to "a", 700 to "a", 5000 to "b", 9000 to "c").forEach { (t, h) -> click(desktop, t, h) }
        jdbc.update("INSERT INTO rec_error (page_view_id, t_ms, message) VALUES (?, 2000, 'x is not defined')", desktop)
        val secondPage = pv("boat4you", 25, visitId = visitId, url = "https://www.boat4you.com/search", pageType = "SEARCH")
        val mobile = pv("catamaran-greece", 10, viewportW = 390, url = "https://www.catamaran-charter-greece.com/", pageType = "HOME")
        pv("boat4you", 5, status = "OPEN")
        pv("boat4you", 6, purged = true)
        val (staffVisitor, staffVisit) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", now.minus(Duration.ofMinutes(8)))
        jdbc.update("UPDATE cc_visitor SET is_staff = TRUE WHERE id = ?", staffVisitor)
        pv("boat4you", 8, visitId = staffVisit)
        return World(desktop, mobile, visitId, chatId, secondPage)
    }

    @Test
    fun `closed playable recordings newest first, with device, clicks, rage clicks, errors, inquiry and chat - open, purged and staff hidden`() {
        val w = world()
        val rows = all()
        rows.map { it.id } shouldContainExactly listOf(w.mobile, w.secondPage, w.desktop)
        val d = rows.single { it.id == w.desktop }
        d.device shouldBe "DESKTOP"
        d.clicks shouldBe 5
        d.rageClicks shouldBe 1
        d.errors shouldBe 1
        d.inquiry shouldBe true
        d.chat shouldBe true
        d.visitId shouldBe w.visitId
        val m = rows.single { it.id == w.mobile }
        m.device shouldBe "MOBILE"
        m.inquiry shouldBe false
        m.chat shouldBe false
    }

    @Test
    fun `filters - site, device, page type, rage only, without inquiry, visit, conversation and the range`() {
        val w = world()
        val base = RecFilter(from = now.minus(Duration.ofDays(7)), to = now)
        all(base.copy(siteId = "catamaran-greece")).map { it.id } shouldContainExactly listOf(w.mobile)
        all(base.copy(device = "MOBILE")).map { it.id } shouldContainExactly listOf(w.mobile)
        all(base.copy(pageType = "SEARCH")).map { it.id } shouldContainExactly listOf(w.secondPage)
        all(base.copy(rageOnly = true)).map { it.id } shouldContainExactly listOf(w.desktop)
        all(base.copy(noInquiry = true)).map { it.id } shouldContainExactly listOf(w.mobile)
        all(base.copy(visitId = w.visitId)).map { it.id } shouldContainExactly listOf(w.secondPage, w.desktop)
        all(base.copy(conversationId = w.chatId)).map { it.id } shouldContainExactly listOf(w.secondPage, w.desktop)
        all(base.copy(from = now.minus(Duration.ofMinutes(20)))).map { it.id } shouldContainExactly listOf(w.mobile)
    }

    @Test
    fun `pages of the list follow on from the last row`() {
        val w = world()
        val base = RecFilter(from = now.minus(Duration.ofDays(7)), to = now, size = 2)
        val first = all(base)
        first.map { it.id } shouldContainExactly listOf(w.mobile, w.secondPage)
        all(base.copy(before = first.last().startedAt, beforeId = first.last().id)).map { it.id } shouldContainExactly listOf(w.desktop)
    }

    @Test
    fun `a recording's details - the other pages of its visit, clicks, the start of each rage burst and errors`() {
        val w = world()
        val d = query.detail(w.desktop).shouldNotBeNull()
        d.row.id shouldBe w.desktop
        d.visitPages.map { it.id } shouldContainExactly listOf(w.desktop, w.secondPage)
        d.clicks.map { it.tMs } shouldContainExactly listOf(100, 400, 700, 5000, 9000)
        d.rage shouldContainExactly listOf(100)
        d.errors.map { it.message } shouldContainExactly listOf("x is not defined")
        query.detail(-1) shouldBe null
    }

    @Test
    fun `archived stylesheets are looked up by path on the recording's own site`() {
        jdbc.update(
            "INSERT INTO rec_asset (host, url, sha256, path, content_type, bytes) VALUES ('www.boat4you.com', " +
                "'https://www.boat4you.com/_next/static/chunks/x1.css', ?, 'www.boat4you.com/abc.css', 'text/css', 10)",
            "a".repeat(64),
        )
        query.assets("boat4you", listOf("/_next/static/chunks/x1.css", "/_next/static/chunks/missing.css", "/etc/passwd")) shouldBe
            mapOf("/_next/static/chunks/x1.css" to "/replay-assets/www.boat4you.com/abc.css")
        query.assets("catamaran-greece", listOf("/_next/static/chunks/x1.css")) shouldBe emptyMap()
    }
}
