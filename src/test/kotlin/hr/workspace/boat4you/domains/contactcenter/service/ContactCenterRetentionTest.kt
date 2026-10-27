package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** The retention purges on PostgreSQL 18 with the real V9_96-V9_98 schema: FK rules, partial indexes and the step order. */
class ContactCenterRetentionTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val retention = ContactCenterRetention(jdbc, CcSettings(jdbc, Duration.ZERO), batch = 2)

    private fun id(
        sql: String,
        vararg a: Any?,
    ): Long = jdbc.queryForObject("$sql RETURNING id", Long::class.java, *a)!!

    private fun visitor(ago: String) =
        id(
            "INSERT INTO cc_visitor (visitor_key, first_site_id, last_seen_at) VALUES (gen_random_uuid()::text, 'boat4you', now() - CAST(? AS interval))",
            ago,
        )

    private fun visit(
        v: Long,
        ago: String,
    ) = id(
        "INSERT INTO cc_visit (visitor_id, site_id, started_at, last_seen_at, ip, country_code) " +
            "VALUES (?, 'boat4you', now() - CAST(? AS interval), now() - CAST(? AS interval), '203.0.113.7', 'US')",
        v,
        ago,
        ago,
    )

    private fun session(
        ago: String,
        flagged: Boolean = false,
        visit: Long? = null,
        contact: Long? = null,
        deleted: Boolean = false,
    ) = id(
        "INSERT INTO ai_chat_session (token, last_activity_at, flagged, visit_id, contact_id, deleted_at) " +
            "VALUES (gen_random_uuid()::text, now() - CAST(? AS interval), ?, ?, ?, CASE WHEN ? THEN now() END)",
        ago,
        flagged,
        visit,
        contact,
        deleted,
    )

    private fun exists(
        table: String,
        id: Long,
    ) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE id = ?", Int::class.java, id) == 1

    @Test
    fun `raw page views and events go after 13 months, in batches`() {
        val v = visit(visitor("1 day"), "1 day")
        repeat(5) { jdbc.update("INSERT INTO cc_page_view (visit_id, path, viewed_at) VALUES (?, '/', now() - interval '14 months')", v) }
        val kept = id("INSERT INTO cc_page_view (visit_id, path, viewed_at) VALUES (?, '/', now() - interval '12 months')", v)
        jdbc.update("INSERT INTO cc_event (visit_id, type, created_at) VALUES (?, 'CLICK', now() - interval '14 months')", v)
        retention.purge().let {
            it.pageViews shouldBe 5
            it.events shouldBe 1
        }
        jdbc.queryForList("SELECT id FROM cc_page_view", Long::class.java) shouldBe listOf(kept)
    }

    @Test
    fun `conversations go after 24 months, flagged evidence never (ADMIN-5, BACKEND-30)`() {
        val old = session("25 months")
        val oldDeleted = session("25 months", deleted = true)
        val evidence = session("25 months", flagged = true)
        val recent = session("23 months")
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, 'USER', 'hi')", old)
        jdbc.update("INSERT INTO cc_search (source, site_id, chat_session_id) VALUES ('AI_CHAT', 'boat4you', ?)", old)
        retention.purge().chatSessions shouldBe 2
        listOf(old, oldDeleted).forEach { exists("ai_chat_session", it) shouldBe false }
        listOf(evidence, recent).forEach { exists("ai_chat_session", it) shouldBe true }
        jdbc.queryForObject("SELECT count(*) FROM ai_chat_message", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT chat_session_id FROM cc_search", Long::class.java).shouldBeNull()
    }

    @Test
    fun `visits, visitors and contacts go after 24 months unless evidence still points at them`() {
        val gone = visitor("25 months")
        val goneVisit = visit(gone, "25 months")
        val kept = visitor("25 months")
        val keptVisit = visit(kept, "25 months")
        val contactGone = id("INSERT INTO cc_contact (name, updated_at) VALUES ('Old', now() - interval '25 months')")
        jdbc.update("INSERT INTO cc_contact_tag (contact_id, tag) VALUES (?, 'vip')", contactGone)
        val contactKept = id("INSERT INTO cc_contact (name, updated_at) VALUES ('Probe', now() - interval '25 months')")
        session("25 months", flagged = true, visit = keptVisit, contact = contactKept)
        retention.purge().let {
            it.visits shouldBe 1
            it.visitors shouldBe 1
            it.contacts shouldBe 1
        }
        exists("cc_visit", goneVisit) shouldBe false
        exists("cc_visitor", gone) shouldBe false
        exists("cc_contact", contactGone) shouldBe false
        exists("cc_visit", keptVisit) shouldBe true
        exists("cc_visitor", kept) shouldBe true
        exists("cc_contact", contactKept) shouldBe true
        jdbc.queryForObject("SELECT count(*) FROM cc_contact_tag", Int::class.java) shouldBe 0
    }

    @Test
    fun `the full IP is cleared after 90 days, the country stays, evidence keeps it`() {
        val v = visitor("1 day")
        val old = visit(v, "91 days")
        val young = visit(v, "89 days")
        val evidence = visit(v, "91 days")
        session("1 day", flagged = true, visit = evidence)

        fun chat(
            ago: String,
            flagged: Boolean = false,
        ) = id(
            "INSERT INTO ai_chat_session (token, created_at, ip, country_code, flagged) " +
                "VALUES (gen_random_uuid()::text, now() - CAST(? AS interval), '198.51.100.9', 'DE', ?)",
            ago,
            flagged,
        )
        val oldChat = chat("91 days")
        val youngChat = chat("89 days")
        val probeChat = chat("91 days", flagged = true)
        retention.purge().let {
            it.ipsCleared shouldBe 1
            it.chatIpsCleared shouldBe 1
        }
        jdbc.queryForMap("SELECT ip, country_code FROM cc_visit WHERE id = ?", old) shouldBe mapOf("ip" to null, "country_code" to "US")
        listOf(young, evidence).forEach { jdbc.queryForObject("SELECT ip FROM cc_visit WHERE id = ?", String::class.java, it) shouldBe "203.0.113.7" }
        jdbc.queryForMap("SELECT ip, country_code FROM ai_chat_session WHERE id = ?", oldChat) shouldBe mapOf("ip" to null, "country_code" to "DE")
        listOf(youngChat, probeChat).forEach {
            jdbc.queryForObject("SELECT ip FROM ai_chat_session WHERE id = ?", String::class.java, it) shouldBe "198.51.100.9"
        }
    }

    @Test
    fun `cc_retention_ip_days moves the IP limit`() {
        jdbc.update("INSERT INTO settings (name, value, created, entity_status) VALUES ('cc.retention.ip.days', '180', now(), 'ACTIVE')")
        visit(visitor("1 day"), "91 days")
        retention.purge().ipsCleared shouldBe 0
    }

    @Test
    fun `a second run finds nothing`() {
        session("25 months")
        visit(visitor("25 months"), "25 months")
        retention.purge()
        retention.purge() shouldBe ContactCenterRetention.Result(0, 0, 0, 0, 0, 0, 0, 0)
    }
}
