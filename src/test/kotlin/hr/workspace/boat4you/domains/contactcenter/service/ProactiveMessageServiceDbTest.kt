package hr.workspace.boat4you.domains.contactcenter.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hr.workspace.boat4you.domains.chat.service.GeoIpService
import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import hr.workspace.boat4you.domains.contactcenter.liveEntry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ProactiveMessageServiceDbTest {
    // a new database, registry and entry per test method (JUnit's default lifecycle), in this declaration order
    private val jdbc = ContactCenterTestDb.ready()
    private val clock = CcTestClock()
    private val key = "a0000000-0000-4000-8000-00000000000a"
    private val registry = LiveVisitorRegistry().also { it.clock = clock }
    private val service = ProactiveMessageService(jdbc, ccTx(jdbc), registry, GeoIpService("/nonexistent.mmdb")).also { it.clock = clock }
    private val entry: LiveEntry =
        ccVisit(jdbc, key, "boat4you", clock.now).let { (visitorId, visitId) ->
            liveEntry(visitorKey = key, visitorId = visitorId, visitId = visitId, lastBeatAt = clock.now).also { registry.register(it) }
        }

    private fun session(
        site: String,
        status: String = "AI",
        flagged: Boolean = false,
        ip: String? = null,
    ) = jdbc.queryForObject(
        "INSERT INTO ai_chat_session (token, status, site_id, visitor_id, flagged, admin_unread, ip, locked_at) VALUES (?, ?, ?, ?, ?, true, ?, ?) RETURNING id",
        Long::class.java,
        UUID.randomUUID().toString(),
        status,
        site,
        entry.visitorId,
        flagged,
        ip,
        if (flagged) Timestamp.from(clock.now) else null,
    )

    @Test
    fun `enqueue results`() {
        service.enqueueProactive("b0000000-0000-4000-8000-00000000000b", "Hi", 7) shouldBe ProactiveResult.NOT_LIVE
        service.enqueueProactive(key, "   ", 7) shouldBe ProactiveResult.INVALID_TEXT
        repeat(3) { service.enqueueProactive(key, "Hi", 7) shouldBe ProactiveResult.QUEUED }
        service.enqueueProactive(key, "Hi", 7) shouldBe ProactiveResult.QUEUE_FULL
        entry.bot = true
        service.enqueueProactive(key, "Hi", 7) shouldBe ProactiveResult.BOT
    }

    @Test
    fun `no session yet - a HUMAN session is created and the token returned`() {
        service.enqueueProactive(key, "  Bok! " + "x".repeat(1500), 7)
        val d = service.deliver(entry)!!
        d.messages.single().text.length shouldBe 1000
        jdbc.queryForMap("SELECT status, site_id, visitor_id, visit_id, admin_unread, flagged FROM ai_chat_session WHERE token = ?", d.chatToken).let {
            it["status"] shouldBe "HUMAN"
            it["site_id"] shouldBe "boat4you"
            it["visitor_id"] shouldBe entry.visitorId
            it["visit_id"] shouldBe entry.visitId
            it["admin_unread"] shouldBe false
            it["flagged"] shouldBe false
        }
        jdbc.queryForMap("SELECT role, author_user_id FROM ai_chat_message").let {
            it["role"] shouldBe "ADMIN"
            it["author_user_id"] shouldBe 7L
        }
        jdbc.queryForMap("SELECT taken_over_at, first_human_response_at, first_response_at FROM ai_chat_session WHERE token = ?", d.chatToken).let {
            it["taken_over_at"] shouldNotBe null
            it["first_human_response_at"] shouldBe null
            it["first_response_at"] shouldBe null // the visitor never wrote
        }
        service.deliver(entry) shouldBe null
    }

    @Test
    fun `a proactive answer after the visitor wrote counts as the human response`() { // like adminReply after P1a Task 8
        val id = session("boat4you", status = "HUMAN_REQUESTED")
        jdbc.update("UPDATE ai_chat_session SET handoff_requested_at = ? WHERE id = ?", Timestamp.from(clock.now.minusSeconds(60)), id)
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, 'USER', 'Can a person call me?')", id)
        service.enqueueProactive(key, "Bok, ovdje Mario", 7)
        service.deliver(entry)
        jdbc.queryForMap("SELECT taken_over_at, first_human_response_at, first_response_by FROM ai_chat_session WHERE id = ?", id).let {
            (it["taken_over_at"] as Timestamp).toInstant() shouldBe clock.now
            (it["first_human_response_at"] as Timestamp).toInstant() shouldBe clock.now
            it["first_response_by"] shouldBe "HUMAN"
        }
    }

    @Test
    fun `same-site session gets the message and becomes HUMAN like adminReply`() { // ADMIN-8, ADMIN-14, BACKEND-4
        val id = session("boat4you")
        service.enqueueProactive(key, "Bok", 7)
        service.deliver(entry)!!.chatToken shouldBe jdbc.queryForObject("SELECT token FROM ai_chat_session WHERE id = ?", String::class.java, id)
        jdbc.queryForMap("SELECT status, admin_unread FROM ai_chat_session WHERE id = ?", id).let {
            it["status"] shouldBe "HUMAN"
            it["admin_unread"] shouldBe false
        }
    }

    @Test
    fun `a locked session stays flagged after Mario writes`() { // ADMIN-9
        val id = session("boat4you", status = "LOCKED", flagged = true)
        service.enqueueProactive(key, "Bok", 7)
        service.deliver(entry)
        jdbc.queryForMap("SELECT status, flagged FROM ai_chat_session WHERE id = ?", id).let {
            it["status"] shouldBe "HUMAN"
            it["flagged"] shouldBe true
        }
    }

    @Test
    fun `a session on another site is never reused`() {
        val other = session("catamaran-greece")
        service.enqueueProactive(key, "Bok", 7)
        val d = service.deliver(entry)!!
        d.chatToken shouldNotBe jdbc.queryForObject("SELECT token FROM ai_chat_session WHERE id = ?", String::class.java, other)
    }

    @Test
    fun `repeat-offender IP starts the new session flagged`() { // BACKEND-6
        val ipEntry =
            liveEntry(
                visitorKey = "c0000000-0000-4000-8000-00000000000c",
                visitorId = entry.visitorId,
                visitId = entry.visitId,
                lastBeatAt = clock.now,
                ip = "198.51.100.9",
            )
        repeat(2) {
            jdbc.update(
                "INSERT INTO ai_chat_session (token, status, flagged, ip, locked_at) VALUES (?, 'LOCKED', true, '198.51.100.9', ?)",
                UUID.randomUUID().toString(),
                Timestamp.from(clock.now.minusSeconds(3600)),
            )
        }
        registry.register(ipEntry)
        service.enqueueProactive(ipEntry.visitorKey, "Bok", 7)
        val d = service.deliver(ipEntry)!!
        jdbc.queryForMap("SELECT status, flagged, lock_reason FROM ai_chat_session WHERE token = ?", d.chatToken).let {
            it["status"] shouldBe "HUMAN"
            it["flagged"] shouldBe true
            it["lock_reason"] shouldBe "IP:REPEAT_OFFENDER:IP_REPEAT"
        }
    }

    // The tests below go beyond the brief: the failure path and the SQL guards that the rows above do not isolate.

    @Test
    fun `a failed delivery stores nothing and the messages wait for the next beat, in order`() {
        val logger = LoggerFactory.getLogger(ProactiveMessageService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        logger.isAdditive = false // the expected WARN is asserted below, not printed
        try {
            service.enqueueProactive(key, "one", 7)
            service.enqueueProactive(key, "two", 7)
            jdbc.execute("ALTER TABLE ai_chat_message RENAME TO ai_chat_message_away")
            service.deliver(entry) shouldBe null
            jdbc.execute("ALTER TABLE ai_chat_message_away RENAME TO ai_chat_message")
            jdbc.queryForObject("SELECT count(*) FROM ai_chat_session", Long::class.java) shouldBe 0L // the new session went with the rollback
            registry.liveSnapshot().single().pendingProactive shouldBe 2
            appender.list.count { it.level == Level.WARN } shouldBe 1
            val d = service.deliver(entry)!!
            d.messages.map { it.text } shouldBe listOf("one", "two")
            d.messages.map { it.createdAt } shouldBe listOf(clock.now, clock.now)
            d.messages.map { it.id } shouldBe jdbc.queryForList("SELECT id FROM ai_chat_message ORDER BY id", Long::class.java)
            registry.liveSnapshot().single().pendingProactive shouldBe 0
        } finally {
            logger.detachAppender(appender)
            logger.isAdditive = true
        }
    }

    @Test
    fun `the human-response stamps are set once, and an earlier AI answer stays the first response`() {
        val id = session("boat4you")
        val aiAnswer = clock.now.minusSeconds(3600)
        jdbc.update("UPDATE ai_chat_session SET first_response_at = ?, first_response_by = 'AI' WHERE id = ?", Timestamp.from(aiAnswer), id)
        jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, 'USER', 'Hello')", id)
        val firstDelivery = clock.now
        service.enqueueProactive(key, "Bok", 7)
        service.deliver(entry)
        clock.advance(Duration.ofMinutes(10))
        service.enqueueProactive(key, "Evo me opet", 7)
        service.deliver(entry)
        jdbc
            .queryForMap(
                "SELECT taken_over_at, first_human_response_at, first_response_at, first_response_by, last_activity_at FROM ai_chat_session WHERE id = ?",
                id,
            ).let {
                (it["taken_over_at"] as Timestamp).toInstant() shouldBe firstDelivery
                (it["first_human_response_at"] as Timestamp).toInstant() shouldBe firstDelivery
                (it["first_response_at"] as Timestamp).toInstant() shouldBe aiAnswer
                it["first_response_by"] shouldBe "AI"
                (it["last_activity_at"] as Timestamp).toInstant() shouldBe clock.now // the activity time follows every delivery
            }
    }

    @Test
    fun `the latest session of this visitor on this site that is not deleted is used`() {
        fun setActivity(
            id: Long?,
            at: Instant,
        ) = jdbc.update("UPDATE ai_chat_session SET last_activity_at = ? WHERE id = ?", Timestamp.from(at), id)
        val older = session("boat4you")
        val newer = session("boat4you")
        val deleted = session("boat4you")
        val (otherVisitorId, _) = ccVisit(jdbc, "d0000000-0000-4000-8000-00000000000d", "boat4you", clock.now)
        val ofSomeoneElse =
            jdbc.queryForObject(
                "INSERT INTO ai_chat_session (token, status, site_id, visitor_id) VALUES (?, 'AI', 'boat4you', ?) RETURNING id",
                Long::class.java,
                UUID.randomUUID().toString(),
                otherVisitorId,
            )
        setActivity(older, clock.now.minusSeconds(7200))
        setActivity(newer, clock.now.minusSeconds(3600))
        setActivity(deleted, clock.now)
        jdbc.update("UPDATE ai_chat_session SET deleted_at = ? WHERE id = ?", Timestamp.from(clock.now), deleted)
        setActivity(ofSomeoneElse, clock.now)
        service.enqueueProactive(key, "Bok", 7)
        service.deliver(entry)!!.chatToken shouldBe jdbc.queryForObject("SELECT token FROM ai_chat_session WHERE id = ?", String::class.java, newer)
        jdbc.queryForList("SELECT id FROM ai_chat_session WHERE status = 'HUMAN'", Long::class.java) shouldBe listOf(newer)
    }

    @Test
    fun `only locks of the last 24 hours flag a new session, and it records when it was locked`() {
        fun lockTwice(
            ip: String,
            age: Duration,
        ) = repeat(2) {
            jdbc.update(
                "INSERT INTO ai_chat_session (token, status, flagged, ip, locked_at) VALUES (?, 'LOCKED', true, ?, ?)",
                UUID.randomUUID().toString(),
                ip,
                Timestamp.from(clock.now.minus(age)),
            )
        }
        lockTwice("198.51.100.9", Duration.ofHours(23))
        lockTwice("198.51.100.10", Duration.ofHours(25))
        val recent =
            liveEntry(
                visitorKey = "c0000000-0000-4000-8000-00000000000c",
                visitorId = entry.visitorId,
                visitId = entry.visitId,
                lastBeatAt = clock.now,
                ip = "198.51.100.9",
            )
        val staleKey = "d0000000-0000-4000-8000-00000000000d"
        val (staleVisitorId, staleVisitId) = ccVisit(jdbc, staleKey, "boat4you", clock.now)
        val stale =
            liveEntry(
                visitorKey = staleKey,
                visitorId = staleVisitorId,
                visitId = staleVisitId,
                lastBeatAt = clock.now,
                ip = "198.51.100.10",
            )
        listOf(recent, stale).forEach {
            registry.register(it)
            service.enqueueProactive(it.visitorKey, "Bok", 7)
        }
        val flagged = service.deliver(recent)!!.chatToken
        val ordinary = service.deliver(stale)!!.chatToken
        jdbc.queryForMap("SELECT flagged, lock_reason, locked_at FROM ai_chat_session WHERE token = ?", flagged).let {
            it["flagged"] shouldBe true
            (it["locked_at"] as Timestamp).toInstant() shouldBe clock.now
        }
        jdbc.queryForMap("SELECT flagged, lock_reason, locked_at FROM ai_chat_session WHERE token = ?", ordinary).let {
            it["flagged"] shouldBe false
            it["lock_reason"] shouldBe null
            it["locked_at"] shouldBe null
        }
    }

    @Test
    fun `the new session gets the visit's locale cut to the 8 characters of its column, or en`() {
        service.enqueueProactive(key, "Bok", 7)
        val withoutLocale = service.deliver(entry)!!.chatToken
        val otherKey = "e0000000-0000-4000-8000-00000000000e"
        val (otherVisitorId, otherVisitId) = ccVisit(jdbc, otherKey, "boat4you", clock.now)
        val longLocale =
            LiveEntry(
                visitId = otherVisitId,
                visitKey = UUID.randomUUID().toString(),
                visitorId = otherVisitorId,
                visitorKey = otherKey,
                siteId = "boat4you",
                startedAt = clock.now,
                returning = false,
                countryCode = null,
                ip = null,
                locale = "zh-Hant-TW", // cc_visit.locale holds 10 characters, ai_chat_session.locale only 8
                deviceClass = "DESKTOP",
                browser = null,
                os = null,
                channel = "DIRECT",
                fromSiteId = null,
                staff = false,
                bot = false,
                lastBeatAt = clock.now,
            )
        registry.register(longLocale)
        service.enqueueProactive(otherKey, "Bok", 7)
        val cut = service.deliver(longLocale)!!.chatToken
        jdbc.queryForObject("SELECT locale FROM ai_chat_session WHERE token = ?", String::class.java, withoutLocale) shouldBe "en"
        jdbc.queryForObject("SELECT locale FROM ai_chat_session WHERE token = ?", String::class.java, cut) shouldBe "zh-Hant-"
    }
}
