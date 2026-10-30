package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.liveEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LiveVisitorRegistryTest {
    private val clock = CcTestClock()
    private val reg = LiveVisitorRegistry().also { it.clock = clock }

    private fun pending(t: String = "Hi") = PendingProactive(t, 7, clock.now)

    @Test
    fun `registry never holds more than 5000 entries`() {
        repeat(LiveVisitorRegistry.MAX_ENTRIES - 1) { reg.register(liveEntry(lastBeatAt = clock.now)) shouldBe true }
        val bot = liveEntry(bot = true, lastBeatAt = clock.now)
        reg.register(bot) shouldBe true
        reg.register(liveEntry(lastBeatAt = clock.now)) shouldBe true // the bot entry is evicted first
        reg.get(bot.visitKey) shouldBe null
        reg.register(liveEntry(lastBeatAt = clock.now)) shouldBe false
        reg.size() shouldBe LiveVisitorRegistry.MAX_ENTRIES
        reg.rejected() shouldBe 1L
    }

    @Test
    fun `snapshot excludes bots, staff and entries silent for 60 s`() {
        val human = liveEntry(lastBeatAt = clock.now)
        listOf(human, liveEntry(bot = true, lastBeatAt = clock.now), liveEntry(staff = true, lastBeatAt = clock.now)).forEach { reg.register(it) }
        reg.liveSnapshot().map { it.visitKey } shouldBe listOf(human.visitKey)
        clock.advance(Duration.ofSeconds(61))
        reg.liveSnapshot() shouldBe emptyList()
        reg.evictExpired() shouldBe 3
    }

    @Test
    fun `page view rate and pending caps`() {
        val e = liveEntry(lastBeatAt = clock.now)
        reg.register(e)
        (1..30).forEach { e.notePageView(it, clock.now.toEpochMilli() + it) shouldBe LiveEntry.PvNote.NEW }
        e.notePageView(5, clock.now.toEpochMilli() + 40) shouldBe LiveEntry.PvNote.DUPLICATE
        e.notePageView(31, clock.now.toEpochMilli() + 50) shouldBe LiveEntry.PvNote.NEW_OVER_RATE
        e.pageCount shouldBe 31
        repeat(3) { reg.offerPending(e, pending()) shouldBe ProactiveResult.QUEUED }
        reg.offerPending(e, pending()) shouldBe ProactiveResult.QUEUE_FULL
        reg.liveSnapshot().single().pendingProactive shouldBe 3
        reg.remove(e.visitKey) // frees its 3 from the global count
        List(166) { liveEntry(lastBeatAt = clock.now).also { reg.register(it) } }
            .forEach { o -> repeat(3) { reg.offerPending(o, pending()) shouldBe ProactiveResult.QUEUED } } // 498
        val last = liveEntry(lastBeatAt = clock.now).also { reg.register(it) }
        repeat(2) { reg.offerPending(last, pending()) shouldBe ProactiveResult.QUEUED } // 500
        reg.offerPending(last, pending()) shouldBe ProactiveResult.QUEUE_FULL // 501st, global cap
    }

    @Test
    fun `a re-registered visit keeps a new bot flag and bot counts are drained per site`() {
        val e = liveEntry(lastBeatAt = clock.now).also { reg.register(it) }
        reg.register(liveEntry(visitKey = e.visitKey, bot = true, lastBeatAt = clock.now)) shouldBe true // resumed with a bot reason
        e.bot shouldBe true
        reg.liveSnapshot() shouldBe emptyList()
        repeat(2) { reg.noteBot("boat4you") }
        reg.noteBot("catamaran-greece")
        reg.drainBotCounts() shouldBe mapOf("boat4you" to 2, "catamaran-greece" to 1)
        reg.drainBotCounts() shouldBe emptyMap()
    }

    @Test
    fun `drained messages go back in front, in order, and the global count follows`() {
        val e = liveEntry(lastBeatAt = clock.now).also { reg.register(it) }
        reg.offerPending(e, pending("a")) shouldBe ProactiveResult.QUEUED
        reg.offerPending(e, pending("b")) shouldBe ProactiveResult.QUEUED
        val drained = reg.drainPending(e)
        drained.map { it.text } shouldBe listOf("a", "b")
        reg.liveSnapshot().single().pendingProactive shouldBe 0
        reg.drainPending(e) shouldBe emptyList()
        reg.offerPending(e, pending("c")) shouldBe ProactiveResult.QUEUED
        reg.requeuePending(e, drained)
        reg.liveSnapshot().single().pendingProactive shouldBe 3
        reg.drainPending(e).map { it.text } shouldBe listOf("a", "b", "c")
        fillGlobalQueueToTheCap() // all 500 places are free again
    }

    @Test
    fun `an entry removed meanwhile takes no message and leaks no place in the global count`() {
        val gone = liveEntry(lastBeatAt = clock.now).also { reg.register(it) }
        reg.offerPending(gone, pending()) shouldBe ProactiveResult.QUEUED
        reg.remove(gone.visitKey)
        reg.offerPending(gone, pending()) shouldBe ProactiveResult.NOT_LIVE
        reg.requeuePending(gone, listOf(pending()))
        gone.pending.size shouldBe 0
        fillGlobalQueueToTheCap()
    }

    @Test
    fun `a bot entry takes no proactive message`() {
        val bot = liveEntry(bot = true, lastBeatAt = clock.now).also { reg.register(it) }
        reg.offerPending(bot, pending()) shouldBe ProactiveResult.BOT
        bot.pending.size shouldBe 0
    }

    @Test
    fun `the latest entry of a visitor is the one with the most recent beat`() {
        val older = liveEntry(visitorKey = "a0000000-0000-4000-8000-00000000000a", lastBeatAt = clock.now).also { reg.register(it) }
        val newer = liveEntry(visitorKey = "a0000000-0000-4000-8000-00000000000a", lastBeatAt = clock.now.plusSeconds(5)).also { reg.register(it) }
        liveEntry().also { reg.register(it) }
        reg.latestForVisitor(older.visitorKey) shouldBe newer
        reg.latestForVisitor("b0000000-0000-4000-8000-00000000000b") shouldBe null
    }

    @Test
    fun `an entry silent for more than 60 s is not the latest of its visitor, though it is not swept yet`() {
        val e = liveEntry(lastBeatAt = clock.now).also { reg.register(it) }
        clock.advance(Duration.ofSeconds(60))
        reg.latestForVisitor(e.visitorKey) shouldBe e // exactly 60 s is still live, as in liveSnapshot
        clock.advance(Duration.ofSeconds(1))
        reg.latestForVisitor(e.visitorKey) shouldBe null // no message is queued for it (NOT_LIVE) ...
        reg.get(e.visitKey) shouldBe e // ... although evictExpired (every 10 s) has not dropped it yet
    }

    @Test
    fun `a re-registered visit keeps a new staff flag, which no later call clears`() {
        val e = liveEntry(lastBeatAt = clock.now).also { reg.register(it) }
        reg.liveSnapshot().map { it.visitKey } shouldBe listOf(e.visitKey)
        reg.register(liveEntry(visitKey = e.visitKey, staff = true, lastBeatAt = clock.now)) shouldBe true // the staff cookie arrived mid-visit
        e.staff shouldBe true
        reg.liveSnapshot() shouldBe emptyList()
        reg.register(liveEntry(visitKey = e.visitKey, staff = false, lastBeatAt = clock.now)) shouldBe true
        e.staff shouldBe true
    }

    @Test
    fun `no bot count is lost while it is drained`() {
        val total = AtomicInteger()
        val drainedSum = AtomicInteger()
        val noting = CountDownLatch(4)
        val writers =
            List(4) {
                thread {
                    repeat(25_000) { reg.noteBot("boat4you") }
                    total.addAndGet(25_000)
                    noting.countDown()
                }
            }
        while (noting.count > 0L) reg.drainBotCounts().values.forEach { drainedSum.addAndGet(it) }
        writers.forEach { it.join() }
        reg.drainBotCounts().values.forEach { drainedSum.addAndGet(it) }
        drainedSum.get() shouldBe total.get()
    }

    /** Fills the global queue with exactly [LiveVisitorRegistry.MAX_PENDING_TOTAL] messages (3 per visitor), then expects the next one refused. */
    private fun fillGlobalQueueToTheCap() {
        val visitors = List(167) { liveEntry(lastBeatAt = clock.now).also { reg.register(it) } }
        visitors.take(166).forEach { v -> repeat(3) { reg.offerPending(v, pending()) shouldBe ProactiveResult.QUEUED } } // 498
        repeat(2) { reg.offerPending(visitors.last(), pending()) shouldBe ProactiveResult.QUEUED } // 500
        reg.offerPending(visitors.last(), pending()) shouldBe ProactiveResult.QUEUE_FULL
    }
}
