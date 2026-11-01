package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID

/** V9_114 (P6 R1, session recordings): the rules the ingest, the worker and the purge rely on. */
class RecMigrationTest {
    private val jdbc = ContactCenterTestDb.ready()

    private fun pageView(pageNum: Int = 1): Long =
        jdbc.queryForObject(
            "INSERT INTO rec_page_view (site_id, project_id, user_id, session_id, page_num, started_at, last_payload_at) " +
                "VALUES ('boat4you', 'boat4you', 'u1', 's1', ?, now(), now()) RETURNING id",
            Long::class.java,
            pageNum,
        )!!

    @Test
    fun `a sampling share outside 0 to 100 is refused`() {
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO rec_setting (site_id, sample_pct) VALUES ('boat4you', 101)")
        }
        jdbc.update("INSERT INTO rec_setting (site_id, sample_pct, mobile_pct) VALUES ('boat4you', 100, 25)") shouldBe 1
    }

    @Test
    fun `one page view per clarity session and page, one row per payload sequence`() {
        val pv = pageView()
        shouldThrow<DataIntegrityViolationException> { pageView() }
        jdbc.update("INSERT INTO rec_payload (page_view_id, sequence) VALUES (?, 1)", pv)
        jdbc.update("INSERT INTO rec_payload (page_view_id, sequence) VALUES (?, 1) ON CONFLICT DO NOTHING", pv) shouldBe 0
    }

    @Test
    fun `deleting a page view drops its facts, deleting a visit only unlinks its page views`() {
        val (_, visit) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", Instant.now())
        val pv = pageView()
        jdbc.update("UPDATE rec_page_view SET visit_id = ? WHERE id = ?", visit, pv)
        jdbc.update("INSERT INTO rec_click (page_view_id, t_ms, x, y) VALUES (?, 10, 1, 2)", pv)
        jdbc.update("INSERT INTO rec_scroll (page_view_id, t_ms, y) VALUES (?, 10, 300)", pv)
        jdbc.update("INSERT INTO rec_error (page_view_id, t_ms, message) VALUES (?, 10, 'x')", pv)
        jdbc.update("INSERT INTO rec_perf (page_view_id, nav) VALUES (?, '{}'::jsonb)", pv)
        jdbc.update("DELETE FROM cc_visit WHERE id = ?", visit)
        jdbc.queryForObject("SELECT visit_id FROM rec_page_view WHERE id = ?", Long::class.java, pv) shouldBe null
        jdbc.update("DELETE FROM rec_page_view WHERE id = ?", pv)
        listOf("rec_click", "rec_scroll", "rec_error", "rec_perf", "rec_payload").forEach {
            jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) shouldBe 0
        }
    }
}
