package hr.workspace.boat4you.domains.contactcenter.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class TrackerSecurityLogDbTest {
    private val jdbc =
        ContactCenterTestDb.ready().also {
            // SHARED shape of P1c's V9_107 (P1c owns that migration; ContactCenterTestDb does not run it. IF NOT EXISTS stays a no-op once P1c appends V9_107 to CONTACT_CENTER)
            it.execute(
                "CREATE TABLE IF NOT EXISTS cc_security_event (id BIGSERIAL PRIMARY KEY, type VARCHAR(30) NOT NULL, " +
                    "site_id VARCHAR(40), detail TEXT, created_at TIMESTAMP NOT NULL DEFAULT now())",
            )
        }
    private val pv = PageViewIn("pv1-abcdef", "https://www.boat4you.com/", "/", null, "HOME", null, Instant.EPOCH)

    // The expected WARNs (a full writer queue, a missing table) are captured here, not printed
    private val loggers = listOf(TrackerSecurityLog::class.java, TrackerWriteQueue::class.java).map { LoggerFactory.getLogger(it) as Logger }
    private val logged = ListAppender<ILoggingEvent>().also { it.start() }

    private fun warnings() = logged.list.filter { it.level == Level.WARN && it.loggerName == TrackerSecurityLog::class.java.name }

    @BeforeEach
    fun capture() =
        loggers.forEach {
            it.addAppender(logged)
            it.isAdditive = false
        }

    @AfterEach
    fun release() =
        loggers.forEach {
            it.detachAppender(logged)
            it.isAdditive = true
        }

    @Test
    fun `dropped surges and bot bursts are logged once a minute, a missing table never breaks ingest`() {
        val writer = TrackerWriteQueue(jdbc, ccTx(jdbc)) // not started: nothing drains it
        val registry = LiveVisitorRegistry()
        val guard = TrackerSurgeGuard(1)
        val log = TrackerSecurityLog(jdbc, writer, registry, guard)
        repeat(TrackerWriteQueue.CAPACITY + 5) { writer.offer(PageViewRow(1, pv)) } // 5 dropped
        repeat(20) { registry.noteBot("catamaran-greece") }
        repeat(3) { registry.noteBot("boat4you") }
        repeat(2) { guard.allowVisit("europe-yachts") } // 1 refused
        log.flush() shouldBe 3
        jdbc.queryForList("SELECT type, site_id FROM cc_security_event ORDER BY id").map { it["type"] to it["site_id"] } shouldBe
            listOf(
                "SURGE_DROPPED" to null,
                "SURGE_DROPPED" to "europe-yachts",
                "BOT_BURST" to "catamaran-greece",
            )
        log.flush() shouldBe 0
        jdbc.execute("DROP TABLE cc_security_event") // P1c not deployed yet
        writer.offer(PageViewRow(1, pv))
        log.flush() shouldBe 0 // WARN only, no exception
    }

    @Test
    fun `each row says what happened, carries the clock time, and a drop is reported once`() {
        val clock = CcTestClock()
        val writer = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val registry = LiveVisitorRegistry()
        val guard = TrackerSurgeGuard(1).also { it.clock = clock }
        val log = TrackerSecurityLog(jdbc, writer, registry, guard).also { it.clock = clock }
        log.flush() shouldBe 0 // nothing happened: no row

        repeat(TrackerWriteQueue.CAPACITY + 5) { writer.offer(PageViewRow(1, pv)) }
        repeat(3) { guard.allowVisit("catamaran-italy") } // 2 refused
        repeat(20) { registry.noteBot("europe-yachts") }
        repeat(19) { registry.noteBot("boat4you") } // one short of a burst
        log.flush() shouldBe 3
        writer.offer(PageViewRow(1, pv))
        writer.offer(PageViewRow(1, pv)) // 2 more dropped since the last flush
        log.flush() shouldBe 1

        jdbc.queryForList("SELECT type, site_id, detail, created_at FROM cc_security_event ORDER BY id").map {
            listOf(it["type"], it["site_id"], it["detail"], (it["created_at"] as java.sql.Timestamp).toInstant())
        } shouldBe
            listOf(
                listOf("SURGE_DROPPED", null, "tracker write queue full: 5 rows dropped", clock.now),
                listOf("SURGE_DROPPED", "catamaran-italy", "site visit budget: 2 visits refused (429)", clock.now),
                listOf("BOT_BURST", "europe-yachts", "20 bot visits flagged in one minute", clock.now),
                listOf("SURGE_DROPPED", null, "tracker write queue full: 2 rows dropped", clock.now),
            )
        registry.drainBotCounts() shouldBe emptyMap() // the flush drained the sub-threshold count too
        warnings().size shouldBe 0
    }

    @Test
    fun `a failing insert is warned about at most once an hour and is not retried`() {
        val clock = CcTestClock()
        val writer = TrackerWriteQueue(jdbc, ccTx(jdbc))
        val registry = LiveVisitorRegistry()
        val guard = TrackerSurgeGuard(1).also { it.clock = clock }
        val log = TrackerSecurityLog(jdbc, writer, registry, guard).also { it.clock = clock }
        jdbc.execute("DROP TABLE cc_security_event")

        fun refusal() = repeat(2) { guard.allowVisit("boat4you") }
        refusal()
        log.flush() shouldBe 0
        warnings().size shouldBe 1
        refusal()
        log.flush() shouldBe 0
        warnings().size shouldBe 1 // the same hour
        clock.advance(Duration.ofMinutes(59))
        refusal()
        log.flush() shouldBe 0
        warnings().size shouldBe 1
        clock.advance(Duration.ofMinutes(2))
        refusal()
        log.flush() shouldBe 0
        warnings().size shouldBe 2
        guard.drainRefusedVisits() shouldBe emptyMap() // taken by the flush and lost, not kept for a retry
    }
}
