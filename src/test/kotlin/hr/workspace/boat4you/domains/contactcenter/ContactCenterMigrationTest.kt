package hr.workspace.boat4you.domains.contactcenter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * V9_96 - V9_98 on PostgreSQL 18 over the real chat migration chain: the IRIS security columns survive untouched
 * (BACKEND-30), the previous jar can still write sessions and messages (rollback), and every FK has the ON DELETE rule
 * the retention purge relies on (BACKEND-32).
 */
class ContactCenterMigrationTest {
    private val security =
        "SELECT token, status, flagged, lock_reason, locked_at, security_soft_hits, deleted_at, ip, page_trail FROM ai_chat_session WHERE id = 48"

    private fun id(
        jdbc: JdbcTemplate,
        sql: String,
        vararg a: Any?,
    ) = jdbc.queryForObject("$sql RETURNING id", Long::class.java, *a)

    @Test
    fun `IRIS sessions keep every security column and become boat4you (BACKEND-30)`() {
        val jdbc = ContactCenterTestDb.newDatabase().also { ContactCenterTestDb.apply(it, ContactCenterTestDb.CHAT_CHAIN) }
        jdbc.update(
            """INSERT INTO ai_chat_session (id, token, status, flagged, lock_reason, locked_at, security_soft_hits, deleted_at, ip, page_trail)
            VALUES (48, 'tok-48', 'LOCKED', TRUE, 'BACKFILL:probes 4.10.2026', TIMESTAMP '2026-10-04 10:00', 3, TIMESTAMP '2026-10-13 09:00', '31.22.91.227', '["/hr"]')""",
        )
        val before = jdbc.queryForMap(security)
        ContactCenterTestDb.apply(jdbc, ContactCenterTestDb.CONTACT_CENTER)
        jdbc.queryForMap(security) shouldBe before
        jdbc.queryForMap("SELECT site_id, missed, visitor_id, visit_id, contact_id, first_response_at FROM ai_chat_session WHERE id = 48") shouldBe
            mapOf("site_id" to "boat4you", "missed" to false, "visitor_id" to null, "visit_id" to null, "contact_id" to null, "first_response_at" to null)
    }

    @Test
    fun `the current jar can still write sessions and messages (rollback)`() {
        val jdbc = ContactCenterTestDb.ready()
        val s = id(jdbc, "INSERT INTO ai_chat_session (token) VALUES ('t-old')")
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, 'USER', 'hi')", s)
        jdbc.queryForObject("SELECT site_id FROM ai_chat_session WHERE id = ?", String::class.java, s) shouldBe "boat4you"
    }

    @Test
    fun `a client message id is unique per session, absent ids never collide`() {
        val jdbc = ContactCenterTestDb.ready()
        val (a, b) = listOf("t-a", "t-b").map { id(jdbc, "INSERT INTO ai_chat_session (token) VALUES (?)", it) }
        val insert = "INSERT INTO ai_chat_message (session_id, role, content, client_message_id) VALUES (?, 'USER', 'x', ?)"
        jdbc.update(insert, a, "c-0001-abcd")
        shouldThrow<DuplicateKeyException> { jdbc.update(insert, a, "c-0001-abcd") }
        jdbc.update(insert, b, "c-0001-abcd")
        jdbc.update(insert, a, null)
        jdbc.update(insert, a, null)
    }

    @Test
    fun `deleting a visit drops its page views and events and unlinks its session and searches`() {
        val jdbc = ContactCenterTestDb.ready()
        val visitor = id(jdbc, "INSERT INTO cc_visitor (visitor_key, first_site_id) VALUES ('k-1', 'catamaran-greece')")
        val visit = id(jdbc, "INSERT INTO cc_visit (visitor_id, site_id) VALUES (?, 'catamaran-greece')", visitor)
        val pv = id(jdbc, "INSERT INTO cc_page_view (visit_id, path) VALUES (?, '/en/catamarans/lagoon-42')", visit)
        jdbc.update("INSERT INTO cc_event (visit_id, page_view_id, type, payload) VALUES (?, ?, 'CLICK', '{\"x\":1}'::jsonb)", visit, pv)
        val s = id(jdbc, "INSERT INTO ai_chat_session (token, visit_id, visitor_id) VALUES ('t-v', ?, ?)", visit, visitor)
        jdbc.update("INSERT INTO cc_search (source, site_id, chat_session_id, visit_id) VALUES ('AI_CHAT', 'catamaran-greece', ?, ?)", s, visit)
        jdbc.update("DELETE FROM cc_visit WHERE id = ?", visit)
        jdbc.queryForObject("SELECT count(*) FROM cc_page_view", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM cc_event", Int::class.java) shouldBe 0
        jdbc.queryForMap("SELECT visit_id, visitor_id FROM ai_chat_session WHERE id = ?", s) shouldBe mapOf("visit_id" to null, "visitor_id" to visitor)
        jdbc.queryForObject("SELECT visit_id FROM cc_search", Long::class.java).shouldBeNull()
    }

    @Test
    fun `deleting a session or a contact only unlinks, tags go with the contact`() {
        val jdbc = ContactCenterTestDb.ready()
        val c = id(jdbc, "INSERT INTO cc_contact (name) VALUES ('Ana')")
        jdbc.update("INSERT INTO cc_contact_tag (contact_id, tag) VALUES (?, 'vip')", c)
        val visitor = id(jdbc, "INSERT INTO cc_visitor (visitor_key, first_site_id, contact_id) VALUES ('k-2', 'boat4you', ?)", c)
        val s1 = id(jdbc, "INSERT INTO ai_chat_session (token, contact_id) VALUES ('t-1', ?)", c)
        val s2 = id(jdbc, "INSERT INTO ai_chat_session (token, contact_id) VALUES ('t-2', ?)", c)
        jdbc.update("INSERT INTO cc_search (source, site_id, chat_session_id) VALUES ('AI_CHAT', 'boat4you', ?)", s1)
        jdbc.update("DELETE FROM ai_chat_session WHERE id = ?", s1)
        jdbc.queryForObject("SELECT chat_session_id FROM cc_search", Long::class.java).shouldBeNull()
        jdbc.update("DELETE FROM cc_contact WHERE id = ?", c)
        jdbc.queryForObject("SELECT count(*) FROM cc_contact_tag", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT contact_id FROM cc_visitor WHERE id = ?", Long::class.java, visitor).shouldBeNull()
        jdbc.queryForObject("SELECT contact_id FROM ai_chat_session WHERE id = ?", Long::class.java, s2).shouldBeNull()
    }

    @Test
    fun `defaults of the new tables`() {
        val jdbc = ContactCenterTestDb.ready()
        val c = id(jdbc, "INSERT INTO cc_contact (name) VALUES ('Ana')")
        jdbc.queryForMap("SELECT status, email_verified FROM cc_contact WHERE id = ?", c) shouldBe mapOf("status" to "NEW", "email_verified" to false)
        val v = id(jdbc, "INSERT INTO cc_visitor (visitor_key, first_site_id) VALUES ('k-3', 'boat4you')")
        jdbc.queryForMap("SELECT is_staff, visit_count FROM cc_visitor WHERE id = ?", v) shouldBe mapOf("is_staff" to false, "visit_count" to 0)
        val visit = id(jdbc, "INSERT INTO cc_visit (visitor_id, site_id) VALUES (?, 'boat4you')", v)
        jdbc.queryForMap("SELECT channel, is_bot, page_count, ended_at FROM cc_visit WHERE id = ?", visit) shouldBe
            mapOf("channel" to "OTHER", "is_bot" to false, "page_count" to 0, "ended_at" to null)
        val pv = id(jdbc, "INSERT INTO cc_page_view (visit_id, path) VALUES (?, '/')", visit)
        jdbc.queryForObject("SELECT page_type FROM cc_page_view WHERE id = ?", String::class.java, pv) shouldBe "OTHER"
        val cs = id(jdbc, "INSERT INTO cc_search (source, site_id) VALUES ('SITE_SEARCH', 'boat4you')")
        jdbc.queryForMap("SELECT flagged, estimated FROM cc_search WHERE id = ?", cs) shouldBe mapOf("flagged" to false, "estimated" to false)
    }
}
