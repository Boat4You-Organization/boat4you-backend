package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class TrackerSurgeGuardTest {
    private val clock = CcTestClock()
    private val guard = TrackerSurgeGuard(3).also { it.clock = clock }

    /** Reads the base clock, then holds the thread named in [slow] where it is (a thread descheduled across a minute boundary). */
    private class HeldUpClock(
        private val base: Clock,
        private val held: CountDownLatch,
        private val release: CountDownLatch,
    ) : Clock() {
        @Volatile var slow: Thread? = null

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant {
            val t = base.instant()
            if (Thread.currentThread() === slow) {
                held.countDown()
                release.await()
            }
            return t
        }
    }

    @Test
    fun `a thread held up across a minute boundary cannot put the old minute's window back`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heldUp = HeldUpClock(clock, held, release)
        val racing = TrackerSurgeGuard(2).also { it.clock = heldUp }
        val a = thread(start = false, isDaemon = true) { racing.allowVisit("europe-yachts") }
        heldUp.slow = a
        a.start()
        held.await() // A has read 10:00:00 and is held up (before the update without the fix, inside it with the fix)
        clock.advance(Duration.ofSeconds(60)) // 10:01:00
        val b = thread(isDaemon = true) { racing.allowVisit("europe-yachts") }
        b.join(300) // without the fix B is done at once; with it B waits for A's hold on the site's key
        release.countDown()
        a.join()
        b.join()
        racing.allowVisit("europe-yachts") shouldBe true // B used one of the two visits of 10:01
        racing.allowVisit("europe-yachts") shouldBe false // without the fix A put the 10:00 window back and this budget started again
    }

    @Test
    fun `each site has its own visit budget per minute`() {
        repeat(3) { guard.allowVisit("catamaran-greece") shouldBe true }
        guard.allowVisit("catamaran-greece") shouldBe false
        guard.allowVisit("boat4you") shouldBe true // a flood on one site never starves another
        guard.drainRefusedVisits() shouldBe mapOf("catamaran-greece" to 1)
        guard.drainRefusedVisits() shouldBe emptyMap()
        clock.advance(Duration.ofSeconds(60))
        guard.allowVisit("catamaran-greece") shouldBe true
    }

    @Test
    fun `at most 8 requests use the database at once`() {
        repeat(TrackerSurgeGuard.MAX_CONCURRENT) { guard.tryEnter() shouldBe true }
        guard.tryEnter() shouldBe false // after ENTER_WAIT
        guard.exit()
        guard.tryEnter() shouldBe true
    }

    @Test
    fun `the budget is a fixed window of the clock minute and every refusal is counted`() {
        repeat(3) { guard.allowVisit("europe-yachts") shouldBe true }
        clock.advance(Duration.ofSeconds(59)) // 10:00:59, still the minute that began at 10:00:00
        repeat(4) { guard.allowVisit("europe-yachts") shouldBe false }
        guard.drainRefusedVisits() shouldBe mapOf("europe-yachts" to 4)
        clock.advance(Duration.ofSeconds(1)) // 10:01:00, a fresh budget
        repeat(3) { guard.allowVisit("europe-yachts") shouldBe true }
        guard.allowVisit("europe-yachts") shouldBe false
        guard.drainRefusedVisits() shouldBe mapOf("europe-yachts" to 1)
    }

    @Test
    fun `the bulkhead is 8 permits and a 100 ms wait, well below the 35 connections of the pool`() {
        TrackerSurgeGuard.MAX_CONCURRENT shouldBe 8
        TrackerSurgeGuard.ENTER_WAIT shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `a request that finds no permit waits ENTER_WAIT before it gives up`() {
        repeat(TrackerSurgeGuard.MAX_CONCURRENT) { guard.tryEnter() shouldBe true }
        val started = System.nanoTime()
        guard.tryEnter() shouldBe false
        // a lower bound only: never flaky on a slow machine
        Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThanOrEqual (TrackerSurgeGuard.ENTER_WAIT.toMillis() - 10)
    }
}
