package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class TrackerSurgeGuardTest {
    private val clock = CcTestClock()
    private val guard = TrackerSurgeGuard(3).also { it.clock = clock }

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
