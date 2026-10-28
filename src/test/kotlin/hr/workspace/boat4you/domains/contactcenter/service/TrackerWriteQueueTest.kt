package hr.workspace.boat4you.domains.contactcenter.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant

class TrackerWriteQueueTest {
    private val pv = PageViewIn("pv1-abcdef", "https://www.boat4you.com/", "/", null, "HOME", null, Instant.EPOCH)

    private fun queue() = TrackerWriteQueue(JdbcTemplate(), Mockito.mock(PlatformTransactionManager::class.java)) // not started

    @Test
    fun `offer never blocks and drops when full`() {
        val q = TrackerWriteQueue(JdbcTemplate(), Mockito.mock(PlatformTransactionManager::class.java)) // not started
        val started = System.nanoTime()
        repeat(TrackerWriteQueue.CAPACITY) { q.offer(PageViewRow(it.toLong(), pv)) shouldBe true }
        q.offer(PageViewRow(-1, pv)) shouldBe false
        Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan 1_000L
        q.stats() shouldBe WriterStats(queued = 5_000, dropped = 1, written = 0, failed = 0)
    }

    @Test
    fun `the drop warning is logged at most once a minute`() {
        val logger = LoggerFactory.getLogger(TrackerWriteQueue::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            val clock = CcTestClock()
            val q = queue().also { it.clock = clock }
            repeat(TrackerWriteQueue.CAPACITY) { q.offer(PageViewRow(it.toLong(), pv)) }
            repeat(3) { q.offer(PageViewRow(-1, pv)) }
            appender.list.count { it.level == Level.WARN } shouldBe 1
            clock.advance(Duration.ofSeconds(59))
            q.offer(PageViewRow(-1, pv))
            appender.list.count { it.level == Level.WARN } shouldBe 1
            clock.advance(Duration.ofSeconds(2))
            q.offer(PageViewRow(-1, pv))
            appender.list.count { it.level == Level.WARN } shouldBe 2
            q.stats().dropped shouldBe 5
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `touches keep the latest time and the first reason, and a new visit is refused at the cap`() {
        val q = queue()
        val t0 = Instant.parse("2026-10-26T10:00:00Z")
        q.touch(1, t0.plusSeconds(5), null)
        q.touch(1, t0, "pv_rate")
        q.touch(1, t0.plusSeconds(2), "ua:curl")
        q.pendingTouches() shouldBe mapOf(1L to VisitTouch(t0.plusSeconds(5), "pv_rate"))

        repeat(TrackerWriteQueue.MAX_TOUCHES) { q.touch(100L + it, t0, null) } // the last one finds the map full
        q.pendingTouches().size shouldBe TrackerWriteQueue.MAX_TOUCHES
        q.stats().dropped shouldBe 1
        q.touch(-5, t0, null)
        q.pendingTouches().size shouldBe TrackerWriteQueue.MAX_TOUCHES
        q.stats().dropped shouldBe 2
        q.touch(1, t0.plusSeconds(60), null) // a visit that is already pending still merges
        q.pendingTouches().getValue(1).lastSeenAt shouldBe t0.plusSeconds(60)
        q.stats().dropped shouldBe 2
    }
}
