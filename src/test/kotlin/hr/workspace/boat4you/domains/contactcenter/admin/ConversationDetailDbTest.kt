package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class ConversationDetailDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = ConversationQuery(jdbc)
    private val base = Instant.parse("2026-10-30T08:00:00Z")

    private fun conv(
        visitorId: Long? = null,
        status: String = "HUMAN",
        unread: Boolean = true,
        deleted: Boolean = false,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO ai_chat_session (token, status, visitor_id, admin_unread, page_trail, taken_over_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, '/en/yachts|/en/contact', ?, CASE WHEN ? THEN now() END) RETURNING id",
            Long::class.java,
            UUID.randomUUID().toString(),
            status,
            visitorId,
            unread,
            Timestamp.from(base),
            deleted,
        )!!

    private fun pageView(
        visitId: Long,
        path: String,
        minutes: Long,
    ) = jdbc.update(
        "INSERT INTO cc_page_view (visit_id, path, page_type, viewed_at) VALUES (?, ?, 'OTHER', ?)",
        visitId,
        path,
        Timestamp.from(base.plusSeconds(minutes * 60)),
    )

    private fun search(
        source: String,
        sessionId: Long? = null,
        visitId: Long? = null,
        location: String,
        estimated: Boolean = false,
        flagged: Boolean = false,
    ) = jdbc.update(
        "INSERT INTO cc_search (source, site_id, chat_session_id, visit_id, location, estimated, flagged) VALUES (?, 'boat4you', ?, ?, ?, ?, ?)",
        source,
        sessionId,
        visitId,
        location,
        estimated,
        flagged,
    )

    @Test
    fun `detail lists messages in order with author and payload`() {
        val id = conv()
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, 'USER', 'Bok')", id)
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content, payload) VALUES (?, 'ASSISTANT', 'Evo', '{\"yachts\":[]}')", id)
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content, author_user_id) VALUES (?, 'ADMIN', 'Mario ovdje', 7)", id)
        val d = query.detail(id).shouldNotBeNull()
        d.messages.map { it.role } shouldContainExactly listOf("USER", "ASSISTANT", "ADMIN")
        d.messages[1].payload shouldBe "{\"yachts\":[]}"
        d.messages[2].authorUserId shouldBe 7L
        d.messages[0].authorUserId.shouldBeNull()
        d.takenOverAt shouldBe base
        d.row.status shouldBe "HUMAN"
    }

    @Test
    fun `history is the visitor's page views across all sites, newest 200, oldest first`() {
        val key = UUID.randomUUID().toString()
        val (visitorId, greeceVisit) = ccVisit(jdbc, key, "greece", base)
        val (_, b4yVisit) = ccVisit(jdbc, key, "boat4you", base)
        pageView(b4yVisit, "/en/yachts", 3)
        pageView(greeceVisit, "/sailing", 1)
        pageView(b4yVisit, "/en/contact", 5)
        val (_, otherVisit) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", base)
        pageView(otherVisit, "/not-mine", 2)
        val d = query.detail(conv(visitorId)).shouldNotBeNull()
        d.history.map { it.siteId to it.path } shouldContainExactly
            listOf("greece" to "/sailing", "boat4you" to "/en/yachts", "boat4you" to "/en/contact")
        jdbc.update(
            "INSERT INTO cc_page_view (visit_id, path, viewed_at) SELECT ?, '/p' || g, CAST(? AS TIMESTAMP) + g * interval '1 second' FROM generate_series(1, 205) g",
            b4yVisit,
            Timestamp.from(base.plusSeconds(600)),
        )
        val capped = query.detail(conv(visitorId)).shouldNotBeNull().history
        capped shouldHaveSize 200
        capped.first().path shouldBe "/p6"
        capped.last().path shouldBe "/p205"
    }

    @Test
    fun `history is empty and pageTrail is kept for an old IRIS session without visitor`() {
        val d = query.detail(conv()).shouldNotBeNull()
        d.history.shouldBeEmpty()
        d.pageTrail shouldBe "/en/yachts|/en/contact"
    }

    @Test
    fun `searches are the session's rows plus the visitor's site searches, without estimated and flagged`() {
        val (visitorId, visitId) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", base)
        val (_, strangerVisit) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", base)
        val id = conv(visitorId)
        search("AI_CHAT", sessionId = id, location = "Split")
        search("AI_CHAT", sessionId = id, location = "Procjena", estimated = true)
        search("AI_CHAT", sessionId = id, location = "Sumnjivo", flagged = true)
        search("SITE_SEARCH", visitId = visitId, location = "Trogir")
        search("FORM", visitId = visitId, location = "Obrazac")
        search("SITE_SEARCH", visitId = strangerVisit, location = "Tuđe")
        query.detail(id).shouldNotBeNull().searches.map { it.location }.toSet() shouldBe setOf("Split", "Trogir")
    }

    @Test
    fun `markRead clears only the unread flag`() {
        val id = conv(status = "HUMAN_REQUESTED")
        query.markRead(id) shouldBe true
        val row = jdbc.queryForMap("SELECT admin_unread, status, taken_over_at FROM ai_chat_session WHERE id = ?", id)
        row["admin_unread"] shouldBe false
        row["status"] shouldBe "HUMAN_REQUESTED"
        (row["taken_over_at"] as Timestamp).toInstant() shouldBe base
    }

    @Test
    fun `a deleted conversation has no detail and cannot be marked read`() {
        val id = conv(deleted = true)
        query.detail(id).shouldBeNull()
        query.markRead(id) shouldBe false
        query.detail(999_999).shouldBeNull()
    }
}
