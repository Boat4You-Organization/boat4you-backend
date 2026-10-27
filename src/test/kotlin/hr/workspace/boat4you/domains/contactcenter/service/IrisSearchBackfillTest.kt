package hr.workspace.boat4you.domains.contactcenter.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Duration

/** The one-off estimated `cc_search` rows for old IRIS replies, on PostgreSQL 18 with the real chat and contact-center tables. */
class IrisSearchBackfillTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val backfill = IrisSearchBackfill(jdbc, ObjectMapper(), DataSourceTransactionManager(jdbc.dataSource!!))
    private val cards = """{"yachts":[{"name":"Lagoon 42 Blue","startDate":"2026-07-25","endDate":"2026-08-01","dates":"exact"}]}"""

    private fun session(
        token: String,
        flagged: Boolean = false,
        deleted: Boolean = false,
    ) = jdbc.queryForObject(
        "INSERT INTO ai_chat_session (token, flagged, deleted_at) VALUES (?, ?, CASE WHEN ? THEN now() END) RETURNING id",
        Long::class.java,
        token,
        flagged,
        deleted,
    )!!

    private fun message(
        s: Long,
        role: String,
        content: String,
        payload: String? = null,
        at: String = "2026-08-01 10:00",
    ) = jdbc.update(
        "INSERT INTO ai_chat_message (session_id, role, content, payload, created_at) VALUES (?, ?, ?, ?, CAST(? AS timestamp))",
        s,
        role,
        content,
        payload,
        at,
    )

    private fun estimated() =
        jdbc.queryForList(
            "SELECT chat_session_id, country_code, start_date, min_persons, result_count, flagged, site_id " +
                "FROM cc_search WHERE estimated ORDER BY chat_session_id",
        )

    @Test
    fun `every stored card reply becomes one estimated search, soft-deleted and flagged sessions included (FACTS-4)`() {
        val live = session("t-live")
        val gone = session("t-gone", deleted = true)
        val probe = session("t-probe", flagged = true)
        listOf(live, gone, probe).forEach {
            message(it, "USER", "Catamaran in Croatia for 8 people")
            message(it, "ASSISTANT", "Here you go", cards)
        }
        message(live, "ASSISTANT", "Any questions?")
        backfill.runAll() shouldBe IrisSearchBackfill.Result(sessions = 3, rows = 3)
        estimated().map { it["chat_session_id"] to it["flagged"] } shouldBe listOf(live to false, gone to false, probe to true)
        estimated().first().let {
            it["country_code"] shouldBe "HR"
            it["min_persons"] shouldBe 8
            it["result_count"] shouldBe 1
            it["start_date"].toString() shouldBe "2026-07-25"
            it["site_id"] shouldBe "boat4you"
        }
    }

    @Test
    fun `a second run changes nothing`() {
        val s = session("t-twice")
        message(s, "USER", "Croatia, 6 people")
        message(s, "ASSISTANT", "a", cards)
        backfill.runAll()
        val first = estimated()
        backfill.runAll().rows shouldBe 1
        estimated() shouldBe first
    }

    @Test
    fun `replies after the first live search are left to the live recording`() {
        val s = session("t-cut")
        message(s, "USER", "Greece, 6 people")
        message(s, "ASSISTANT", "a", cards, at = "2026-10-20 10:00")
        message(s, "USER", "Croatia")
        message(s, "ASSISTANT", "b", cards, at = "2026-10-28 10:00")
        jdbc.update("INSERT INTO cc_search (source, site_id, chat_session_id, created_at) VALUES ('AI_CHAT', 'boat4you', ?, TIMESTAMP '2026-10-27 09:00')", s)
        backfill.runAll().rows shouldBe 1
        jdbc.queryForObject("SELECT country_code FROM cc_search WHERE estimated", String::class.java) shouldBe "GR"
        jdbc.queryForObject("SELECT count(*) FROM cc_search WHERE NOT estimated", Int::class.java) shouldBe 1
    }

    @Test
    fun `an estimated search takes the session's site and the time of its reply`() {
        val s = session("t-site")
        jdbc.update("UPDATE ai_chat_session SET site_id = 'catamaran-greece' WHERE id = ?", s)
        message(s, "USER", "Greece, 6 people")
        message(s, "ASSISTANT", "a", cards, at = "2026-08-01 10:07:30")
        backfill.runAll().rows shouldBe 1
        jdbc.queryForObject("SELECT site_id FROM cc_search WHERE estimated", String::class.java) shouldBe "catamaran-greece"
        jdbc.queryForObject("SELECT to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') FROM cc_search WHERE estimated", String::class.java) shouldBe "2026-08-01 10:07:30"
    }

    @Test
    fun `the job runs only while cc_backfill_enabled and then switches it off`() {
        val settings = CcSettings(jdbc, Duration.ZERO)
        val job = IrisSearchBackfillJob(backfill, settings)
        val s = session("t-job")
        message(s, "USER", "x")
        message(s, "ASSISTANT", "y", cards)
        job.run()
        estimated() shouldHaveSize 0
        jdbc.update("INSERT INTO settings (name, value, created, entity_status) VALUES ('cc.backfill.enabled', 'true', now(), 'ACTIVE')")
        job.run()
        estimated() shouldHaveSize 1
        settings.backfillEnabled shouldBe false
    }
}
