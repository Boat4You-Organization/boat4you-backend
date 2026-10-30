package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import hr.workspace.boat4you.domains.contactcenter.liveEntry
import hr.workspace.boat4you.domains.contactcenter.service.LiveVisitorRegistry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.UUID

class LiveViewTest {
    private val clock = CcTestClock()
    private val registry = LiveVisitorRegistry().also { it.clock = clock }

    @Test
    fun `no traffic gives an empty list, not an error`() {
        LiveView(registry, JdbcTemplate()).rows().shouldBeEmpty() // a JdbcTemplate without a DataSource fails on any query
    }

    @Test
    fun `each live visitor gets their latest conversation and contact`() {
        val jdbc = ContactCenterTestDb.ready()
        val ana = UUID.randomUUID().toString()
        val (anaId, anaVisit) = ccVisit(jdbc, ana, "boat4you", clock.now)
        val ivo = UUID.randomUUID().toString()
        val (ivoId, ivoVisit) = ccVisit(jdbc, ivo, "greece", clock.now)
        val contact = jdbc.queryForObject("INSERT INTO cc_contact (name) VALUES ('Ana') RETURNING id", Long::class.java)!!

        fun session(
            minutes: Long,
            deleted: Boolean = false,
        ): Long =
            jdbc.queryForObject(
                "INSERT INTO ai_chat_session (token, visitor_id, contact_id, status, admin_unread, last_activity_at, deleted_at) " +
                    "VALUES (?, ?, ?, 'HUMAN', TRUE, ?, CASE WHEN ? THEN now() END) RETURNING id",
                Long::class.java,
                UUID.randomUUID().toString(),
                anaId,
                contact,
                Timestamp.from(clock.now.minusSeconds(minutes * 60)),
                deleted,
            )!!
        session(30)
        val latest = session(10)
        session(1, deleted = true)
        registry.register(liveEntry(visitorKey = ana, visitorId = anaId, visitId = anaVisit, lastBeatAt = clock.now.minusSeconds(5)))
        registry.register(liveEntry(visitorKey = ivo, visitorId = ivoId, visitId = ivoVisit, siteId = "greece", lastBeatAt = clock.now))
        val rows = LiveView(registry, jdbc).rows()
        rows.map { it.visitor.visitorId } shouldBe listOf(ivoId, anaId)
        rows[0].chatSessionId.shouldBeNull()
        rows[0].adminUnread shouldBe false
        rows[1].chatSessionId shouldBe latest
        rows[1].chatStatus shouldBe "HUMAN"
        rows[1].contactName shouldBe "Ana"
        rows[1].adminUnread shouldBe true
    }

    @Test
    fun `rows are capped at 500 and newest first`() {
        val jdbc = ContactCenterTestDb.ready()
        repeat(510) { i -> registry.register(liveEntry(visitorId = 1_000_000L + i, lastBeatAt = clock.now.minusMillis((i * 7L) % 510 * 10))) }
        val rows = LiveView(registry, jdbc).rows()
        rows shouldHaveSize 500
        rows.map { it.visitor.lastBeatAt } shouldBeSortedWith compareByDescending { it }
    }
}
