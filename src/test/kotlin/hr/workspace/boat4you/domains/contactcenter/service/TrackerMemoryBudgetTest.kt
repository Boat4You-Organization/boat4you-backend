package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.liveEntry
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.lang.management.ManagementFactory
import java.time.LocalDate
import java.util.UUID

/**
 * FACTS-3 / design 4: every bounded tracker structure filled to its cap, with the longest values the validator lets through, must stay
 * under 48 MB of heap. The structures are held in locals and read once at the end, so the garbage collector cannot free them before the
 * second measurement.
 */
class TrackerMemoryBudgetTest {
    private val mx = ManagementFactory.getMemoryMXBean()

    private fun usedAfterGc(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(150)
        }
        return mx.heapMemoryUsage.used
    }

    @Test
    fun `all tracker structures at their caps stay under 48 MB of heap`() {
        val before = usedAfterGc()
        val clock = CcTestClock()
        val registry = LiveVisitorRegistry().also { it.clock = clock }
        val writer = TrackerWriteQueue(JdbcTemplate(), Mockito.mock(PlatformTransactionManager::class.java))
        val tokens = HandoffTokenStore().also { it.clock = clock }
        repeat(LiveVisitorRegistry.MAX_ENTRIES) { i ->
            val e = liveEntry(lastBeatAt = clock.now)
            registry.register(e)
            synchronized(e) {
                e.currentPath = "/" + "p".repeat(498) + i
                e.currentTitle = "t".repeat(299) + i
            }
            repeat(32) { e.notePageView(it + i * 100, clock.now.toEpochMilli()) }
            if (i < 167) repeat(3) { registry.offerPending(e, PendingProactive("m".repeat(999) + i, 7, clock.now)) }
        }
        val pv = { i: Int ->
            PageViewIn(
                "pv-%034d".format(i),
                "https://www.boat4you.com/" + "u".repeat(974) + i,
                "/" + "p".repeat(498) + i,
                "t".repeat(299) + i,
                "BOAT",
                "s".repeat(199) + i,
                clock.now,
            )
        }
        repeat(TrackerWriteQueue.CAPACITY) { writer.offer(PageViewRow(it.toLong(), pv(it))) }
        repeat(HandoffTokenStore.MAX_TOKENS) { tokens.issue(UUID.randomUUID().toString(), "catamaran-greece", "boat4you") }
        val delta = usedAfterGc() - before
        println("CC_MEMORY heap_delta_mb=${delta / 1_048_576}")
        delta shouldBeLessThan 48L * 1_048_576
        listOf(registry.size(), writer.stats().queued, tokens.size()).size shouldBe 3 // keep the structures reachable until here
    }

    /**
     * The extreme of the ledger rulings on Tasks 7 and 8: every text outside Latin-1 (the JVM then stores two bytes a character), half of
     * the writer queue as SEARCH events of maximum size, a full touch map and a full dedupe map (filled through its private field, since
     * only committed batches fill it), 167 visitors with three 1000-character messages. The first test measures page views only.
     */
    @Test
    fun `wide-character text and maximum-size SEARCH events in half the queue stay under 48 MB of heap`() {
        val before = usedAfterGc()
        val clock = CcTestClock()
        val registry = LiveVisitorRegistry().also { it.clock = clock }
        val writer = TrackerWriteQueue(JdbcTemplate(), Mockito.mock(PlatformTransactionManager::class.java))
        val tokens = HandoffTokenStore().also { it.clock = clock }
        repeat(LiveVisitorRegistry.MAX_ENTRIES) { i ->
            val e = liveEntry(lastBeatAt = clock.now, ip = "0000:0000:0000:0000:0000:ffff:255.255.255." + "%03d".format(i % 1000))
            registry.register(e)
            synchronized(e) {
                e.currentPath = wide(500, i)
                e.currentTitle = wide(300, i)
            }
            repeat(32) { e.notePageView(it + i * 100, clock.now.toEpochMilli()) }
            if (i < 167) repeat(3) { registry.offerPending(e, PendingProactive(wide(1000, i), 7, clock.now)) }
        }
        repeat(TrackerWriteQueue.CAPACITY / 2) { i ->
            val pv = PageViewIn("pv-%034d".format(i), "https://www.boat4you.com/" + "u".repeat(974) + i, "/" + "p".repeat(498) + i, wide(300, i), "BOAT", wide(200, i), clock.now)
            writer.offer(PageViewRow(i.toLong(), pv))
        }
        repeat(TrackerWriteQueue.CAPACITY / 2) { i ->
            val payload = (0 until 10).joinToString(",", "{", "}") { k -> "\"k${k}_" + "a".repeat(34) + "\":\"" + wide(200, i * 10 + k) + "\"" }
            val search =
                SiteSearchCriteria(
                    "HR",
                    wide(60, i),
                    1_000_000L + i,
                    LocalDate.of(2027, 7, 17),
                    LocalDate.of(2027, 7, 24),
                    12,
                    6,
                    1_000,
                    99_000,
                    "SAILING_YACHT",
                    wide(200, i),
                    100,
                    100,
                )
            val ev = EventIn("ev-%037d".format(i), "SEARCH", "pv-%034d".format(i), wide(200, i), wide(300, i), payload, search, false, clock.now)
            writer.offer(EventRow(i.toLong(), "boat4you", ev))
        }
        repeat(TrackerWriteQueue.MAX_TOUCHES) { writer.touch(it.toLong(), clock.now, "ua:googlebot") }
        @Suppress("UNCHECKED_CAST")
        val seen = TrackerWriteQueue::class.java.getDeclaredField("seen").also { it.isAccessible = true }.get(writer) as MutableMap<String, Long>
        repeat(TrackerWriteQueue.DEDUPE_KEYS) { seen["p:$it:" + "c".repeat(35) + "%05d".format(it)] = it + 1_000L }
        repeat(HandoffTokenStore.MAX_TOKENS) { tokens.issue(UUID.randomUUID().toString(), "catamaran-greece", "boat4you") }
        val delta = usedAfterGc() - before
        println("CC_MEMORY_WIDE heap_delta_mb=${delta / 1_048_576}")
        delta shouldBeLessThan 48L * 1_048_576
        listOf(registry.size(), writer.stats().queued, tokens.size(), seen.size).size shouldBe 4 // keep the structures reachable until here
    }

    /** [length] characters outside Latin-1 (so two bytes each on the heap) that differ per [tag]. */
    private fun wide(
        length: Int,
        tag: Int,
    ): String = "č".repeat(length - 6) + "%06d".format(tag)
}
