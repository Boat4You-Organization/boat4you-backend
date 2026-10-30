package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class ConversationQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = ConversationQuery(jdbc)
    private val base = Instant.parse("2026-10-30T08:00:00Z")

    private fun conv(
        status: String = "AI",
        minutes: Long = 0,
        site: String = "boat4you",
        name: String? = null,
        email: String? = null,
        flagged: Boolean = false,
        deleted: Boolean = false,
        contactId: Long? = null,
        unread: Boolean = false,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO ai_chat_session (token, status, site_id, visitor_name, visitor_email, flagged, deleted_at, contact_id, admin_unread, " +
                "last_activity_at) VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, ?, ?, ?) RETURNING id",
            Long::class.java,
            UUID.randomUUID().toString(),
            status,
            site,
            name,
            email,
            flagged,
            deleted,
            contactId,
            unread,
            Timestamp.from(base.plusSeconds(minutes * 60)),
        )!!

    private fun msg(
        sessionId: Long,
        content: String,
        role: String = "USER",
    ) = jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, ?, ?)", sessionId, role, content)

    private fun contact(
        status: String = "NEW",
        vararg tags: String,
    ): Long {
        val id = jdbc.queryForObject("INSERT INTO cc_contact (status) VALUES (?) RETURNING id", Long::class.java, status)!!
        tags.forEach { jdbc.update("INSERT INTO cc_contact_tag (contact_id, tag) VALUES (?, ?)", id, it) }
        return id
    }

    private fun ids(filter: ConversationFilter = ConversationFilter()) = query.list(filter).map { it.id }

    @Test
    fun `the default list is every non-deleted conversation, newest activity first`() {
        val ai = conv("AI", 1)
        val requested = conv("HUMAN_REQUESTED", 5)
        val human = conv("HUMAN", 3)
        val closed = conv("CLOSED", 3)
        val locked = conv("LOCKED", 2)
        conv("AI", 9, deleted = true)
        ids() shouldContainExactly listOf(requested, closed, human, locked, ai)
    }

    @Test
    fun `needs human is HUMAN_REQUESTED, HUMAN and LOCKED, flagged only is flagged or LOCKED`() {
        conv("AI", 1)
        val requested = conv("HUMAN_REQUESTED", 2)
        val human = conv("HUMAN", 3)
        val locked = conv("LOCKED", 4)
        val flagged = conv("CLOSED", 5, flagged = true)
        ids(ConversationFilter(needsHuman = true)) shouldContainExactly listOf(locked, human, requested)
        ids(ConversationFilter(flaggedOnly = true)) shouldContainExactly listOf(flagged, locked)
    }

    @Test
    fun `the last message is the newest one, cut to 140 characters, with its role`() {
        val a = conv(minutes = 2)
        msg(a, "prva")
        msg(a, "x".repeat(200), role = "ASSISTANT")
        val empty = conv(minutes = 1)
        val rows = query.list(ConversationFilter())
        rows[0].lastMessage shouldBe "x".repeat(140)
        rows[0].lastMessageRole shouldBe "ASSISTANT"
        rows[1].id shouldBe empty
        rows[1].lastMessage.shouldBeNull()
        rows[1].lastMessageRole.shouldBeNull()
    }

    @Test
    fun `search finds name, email and message text, case-insensitive`() {
        val named = conv(minutes = 1, name = "Ana")
        val mailed = conv(minutes = 2, email = "a.b@x.com")
        val texted = conv(minutes = 3)
        msg(texted, "Tražim KATAMARAN za 8 osoba")
        conv(minutes = 4, name = "Ivo")
        ids(ConversationFilter(q = "ANA")) shouldContainExactly listOf(named)
        ids(ConversationFilter(q = "a.b@x")) shouldContainExactly listOf(mailed)
        ids(ConversationFilter(q = " katamaran ")) shouldContainExactly listOf(texted)
    }

    @Test
    fun `search treats percent, underscore and backslash as text`() {
        val percent = conv(minutes = 1)
        msg(percent, "popust 50% na tjedan")
        val near = conv(minutes = 2)
        msg(near, "popust 500 eura")
        val underscore = conv(minutes = 3)
        msg(underscore, "kod a_b")
        val back = conv(minutes = 4)
        msg(back, "putanja C:\\temp")
        ids(ConversationFilter(q = "50%")) shouldContainExactly listOf(percent)
        ids(ConversationFilter(q = "_")) shouldContainExactly listOf(underscore)
        ids(ConversationFilter(q = "\\")) shouldContainExactly listOf(back)
        ids(ConversationFilter(q = "'\"")) shouldHaveSize 0
    }

    @Test
    fun `site, status, tag, contact status, spam and date filters`() {
        val vip = contact("CUSTOMER", "vip")
        val spam = contact("SPAM", "spam")
        val greece = conv("AI", 1, site = "greece", contactId = vip)
        val spammer = conv("HUMAN", 2, contactId = spam)
        val plain = conv("CLOSED", 3)
        ids(ConversationFilter(siteId = "greece")) shouldContainExactly listOf(greece)
        ids(ConversationFilter(status = "CLOSED")) shouldContainExactly listOf(plain)
        ids(ConversationFilter(tag = "vip")) shouldContainExactly listOf(greece)
        ids(ConversationFilter(contactStatus = "CUSTOMER")) shouldContainExactly listOf(greece)
        ids(ConversationFilter(includeSpam = false)) shouldContainExactly listOf(plain, greece)
        ids(ConversationFilter(from = base.plusSeconds(120), to = base.plusSeconds(180))) shouldContainExactly listOf(spammer)
        query.list(ConversationFilter(tag = "vip")).single().tags shouldContainExactly listOf("vip")
    }

    @Test
    fun `keyset paging returns the next page without repeats`() {
        val all = (1L..5L).map { conv(minutes = it % 3) } // two pairs share a timestamp: id breaks the tie
        val expected = ids()
        expected shouldHaveSize all.size
        val first = query.list(ConversationFilter(size = 2))
        val second = query.list(ConversationFilter(size = 2, before = first.last().lastActivityAt, beforeId = first.last().id))
        val third = query.list(ConversationFilter(size = 2, before = second.last().lastActivityAt, beforeId = second.last().id))
        (first + second + third).map { it.id } shouldContainExactly expected
        third shouldHaveSize 1
    }

    @Test
    fun `size is clamped to 1 to 200`() {
        jdbc.update("INSERT INTO ai_chat_session (token) SELECT md5(g::text) FROM generate_series(1, 201) g")
        query.list(ConversationFilter(size = 0)) shouldHaveSize 1
        query.list(ConversationFilter(size = 1000)) shouldHaveSize 200
    }

    @Test
    fun `unread count skips deleted conversations`() {
        conv(unread = true)
        conv(unread = true, deleted = true)
        conv()
        query.unreadCount() shouldBe 1
    }
}
