package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TrackerWriteQueueDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val t0 = Instant.parse("2026-10-26T10:00:00Z")

    private fun pv(id: String) = PageViewIn(id, "https://www.boat4you.com/boat/x-1", "/boat/x-1", "X", "BOAT", "x-1", t0)

    private fun ev(
        id: String,
        type: String = "CLICK",
        pvId: String? = null,
        search: SiteSearchCriteria? = null,
        payload: String? = """{"n":"1"}""",
    ) = EventIn(id, type, pvId, "Book online", null, payload, search, false, t0)

    private fun waitUntil(
        what: String,
        check: () -> Boolean,
    ) {
        val end = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!check()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun writerThreads() = Thread.getAllStackTraces().keys.filter { it.name == "cc-tracker-writer" && it.isAlive }

    @Test
    fun `page views and events are appended once, linked, counted and touched`() {
        val q = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val (_, visitId) = ccVisit(jdbc, "a0000000-0000-4000-8000-000000000001", "boat4you", t0)
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        q.offer(EventRow(visitId, "boat4you", ev("ev1-abcdef", pvId = "pv1-abcdef")))
        q.offer(
            EventRow(
                visitId,
                "boat4you",
                ev(
                    "ev2-abcdef",
                    "SEARCH",
                    search = SiteSearchCriteria("HR", "Split", null, LocalDate.parse("2027-07-17"), null, 6, null, null, null, null, null, null, null),
                ),
            ),
        )
        q.touch(visitId, t0.plusSeconds(20), BotFilter.PV_RATE)
        q.flushOnce(Duration.ZERO) shouldBe 4
        val pvId = jdbc.queryForObject("SELECT id FROM cc_page_view WHERE visit_id = ?", Long::class.java, visitId)
        jdbc.queryForObject("SELECT page_view_id FROM cc_event WHERE label = 'Book online' AND type = 'CLICK'", Long::class.java) shouldBe pvId
        jdbc.queryForObject("SELECT payload->>'n' FROM cc_event WHERE type = 'CLICK'", String::class.java) shouldBe "1"
        jdbc.queryForMap("SELECT source, site_id, location, start_date, min_persons, country_code FROM cc_search").let {
            it["source"] shouldBe "SITE_SEARCH"
            it["location"] shouldBe "Split"
            it["min_persons"] shouldBe 6
            it["country_code"] shouldBe "HR"
        }
        jdbc.queryForMap("SELECT page_count, last_seen_at, is_bot, bot_reason FROM cc_visit WHERE id = ?", visitId).let {
            it["page_count"] shouldBe 1
            (it["last_seen_at"] as Timestamp).toInstant() shouldBe t0.plusSeconds(20)
            it["is_bot"] shouldBe true
            it["bot_reason"] shouldBe "pv_rate"
        }
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        q.flushOnce(Duration.ZERO) // a retried beat
        jdbc.queryForObject("SELECT count(*) FROM cc_page_view", Long::class.java) shouldBe 1L
    }

    @Test
    fun `a flush takes at most 200 rows`() {
        val q = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val (_, visitId) = ccVisit(jdbc, "a0000000-0000-4000-8000-000000000003", "boat4you", t0)
        repeat(250) { q.offer(PageViewRow(visitId, pv("pv-%06d".format(it)))) }
        q.flushOnce(Duration.ZERO) shouldBe 200
        q.flushOnce(Duration.ZERO) shouldBe 50
        jdbc.queryForObject("SELECT page_count FROM cc_visit WHERE id = ?", Int::class.java, visitId) shouldBe 250
    }

    @Test
    fun `an event links to a page view of an earlier flush, and a retried event is stored once`() {
        val q = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val (_, visitId) = ccVisit(jdbc, "a0000000-0000-4000-8000-000000000004", "boat4you", t0)
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        q.offer(PageViewRow(visitId, PageViewIn("pv2-abcdef", "https://www.boat4you.com/", "/", null, "HOME", null, t0)))
        q.flushOnce(Duration.ZERO) shouldBe 2
        q.offer(EventRow(visitId, "boat4you", ev("ev1-abcdef", pvId = "pv1-abcdef")))
        q.offer(EventRow(visitId, "boat4you", ev("ev2-abcdef", "SCROLL", pvId = "pv-never-seen", payload = null)))
        q.flushOnce(Duration.ZERO) shouldBe 2
        q.offer(EventRow(visitId, "boat4you", ev("ev1-abcdef", pvId = "pv1-abcdef")))
        q.flushOnce(Duration.ZERO) shouldBe 1
        val pvId = jdbc.queryForObject("SELECT id FROM cc_page_view WHERE path = '/boat/x-1'", Long::class.java)
        jdbc.queryForObject("SELECT page_view_id FROM cc_event WHERE type = 'CLICK'", Long::class.java) shouldBe pvId
        jdbc.queryForObject("SELECT count(*) FROM cc_event WHERE type = 'CLICK'", Long::class.java) shouldBe 1L
        jdbc.queryForMap("SELECT page_view_id, payload FROM cc_event WHERE type = 'SCROLL'").let {
            it["page_view_id"] shouldBe null
            it["payload"] shouldBe null
        }
        jdbc.queryForMap("SELECT title, boat_slug, page_type FROM cc_page_view WHERE path = '/'").let {
            it["title"] shouldBe null
            it["boat_slug"] shouldBe null
            it["page_type"] shouldBe "HOME"
        }
        jdbc.queryForObject("SELECT page_count FROM cc_visit WHERE id = ?", Int::class.java, visitId) shouldBe 2
    }

    @Test
    fun `a failing batch is counted, not retried, and its keys are not remembered`() {
        val q = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val (_, visitId) = ccVisit(jdbc, "a0000000-0000-4000-8000-000000000005", "boat4you", t0)
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        q.offer(PageViewRow(Long.MAX_VALUE, pv("pv2-abcdef"))) // no such visit: the foreign key refuses the whole batch
        q.flushOnce(Duration.ZERO) shouldBe 2
        q.stats() shouldBe WriterStats(queued = 0, dropped = 0, written = 0, failed = 2)
        jdbc.queryForObject("SELECT count(*) FROM cc_page_view", Long::class.java) shouldBe 0L
        q.flushOnce(Duration.ZERO) shouldBe 0 // nothing is retried
        q.offer(PageViewRow(visitId, pv("pv1-abcdef"))) // the same page view again is new, not a duplicate
        q.flushOnce(Duration.ZERO) shouldBe 1
        q.stats() shouldBe WriterStats(queued = 0, dropped = 0, written = 1, failed = 2)
        jdbc.queryForObject("SELECT count(*) FROM cc_page_view WHERE visit_id = ?", Long::class.java, visitId) shouldBe 1L
        jdbc.queryForObject("SELECT page_count FROM cc_visit WHERE id = ?", Int::class.java, visitId) shouldBe 1
    }

    @Test
    fun `the writer thread flushes on its own and stop writes the rows it had already taken`() {
        val q = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val (_, visitId) = ccVisit(jdbc, "a0000000-0000-4000-8000-000000000006", "boat4you", t0)
        writerThreads().size shouldBe 0
        q.start()
        writerThreads().size shouldBe 1
        writerThreads().single().isDaemon shouldBe true
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        // the writer takes the row, then waits up to FLUSH_EVERY for more: stop() lands in that wait
        waitUntil("the writer to take the row") { q.stats().queued == 0 }
        q.stop()
        writerThreads().size shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM cc_page_view WHERE visit_id = ?", Long::class.java, visitId) shouldBe 1L
        q.stats().written shouldBe 1
    }

    @Test
    fun `stop writes what is still queued`() {
        val q = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val (_, visitId) = ccVisit(jdbc, "a0000000-0000-4000-8000-000000000007", "boat4you", t0)
        q.offer(PageViewRow(visitId, pv("pv1-abcdef")))
        q.touch(visitId, t0.plusSeconds(30), null)
        q.stop() // never started: only the final flush runs
        jdbc.queryForObject("SELECT count(*) FROM cc_page_view WHERE visit_id = ?", Long::class.java, visitId) shouldBe 1L
        (jdbc.queryForObject("SELECT last_seen_at FROM cc_visit WHERE id = ?", Timestamp::class.java, visitId)!!).toInstant() shouldBe
            t0.plusSeconds(30)
    }
}
