package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.chat.service.GeoIpService
import hr.workspace.boat4you.domains.contactcenter.CHROME_UA
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * FACTS-3: RSS and beat latency of the real tracker services under load. Run on demand, `CC_LOAD=1 ./gradlew test --tests '*TrackerLoadRssTest' -i`.
 * JUnit evaluates the condition before it creates the test instance, so a normal run creates no database and starts no container. The services
 * are called directly: the controller's per-site budget and bulkhead are not part of this measurement.
 */
@EnabledIfEnvironmentVariable(named = "CC_LOAD", matches = "1")
class TrackerLoadRssTest {
    private val jdbc = ContactCenterTestDb.ready(poolSize = 8) // CHAT_CHAIN + CONTACT_CENTER, V9_103 included since Task 1
    private val tx = ccTx(jdbc)
    private val registry = LiveVisitorRegistry()
    private val writer = TrackerWriteQueue(jdbc, tx)
    private val urls = TrackerUrlPolicy(BrandRegistry(), "")
    private val visits =
        VisitService(
            jdbc,
            tx,
            registry,
            HandoffTokenStore(),
            GeoIpService("/nonexistent.mmdb"),
            ChannelClassifier(urls),
            DatacenterIpFilter("/nonexistent.mmdb"),
        )
    private val beats = BeatService(visits, registry, writer, ProactiveMessageService(jdbc, tx, registry, GeoIpService("/nonexistent.mmdb")))

    private fun rssKb() =
        ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
            .toLong()

    @Test
    fun `2000 visitors x 10 beats on 16 threads stay under 200 MB RSS and 5 ms p95`() {
        writer.start()
        val before = rssKb()
        val peak = AtomicLong(before)
        val lat = ConcurrentLinkedQueue<Long>()
        val sampler =
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    peak.accumulateAndGet(rssKb()) { a, b -> maxOf(a, b) }
                    Thread.sleep(200)
                }
            }.apply {
                isDaemon = true
                start()
            }
        val pool = Executors.newFixedThreadPool(16)
        (0 until 2000)
            .map { v ->
                pool.submit {
                    val s = visits.startOrResume(VisitCommand("boat4you", UUID.randomUUID().toString(), "203.0.113.${v % 250}", CHROME_UA))
                    // 10 beats x 3 page views = 30 = BotFilter.MAX_PAGE_VIEWS_PER_MINUTE; a 31st would flag the visit as a bot
                    repeat(10) { b ->
                        val t = System.nanoTime()
                        beats.beat(
                            BeatCommand(
                                "boat4you",
                                s.visitorKey,
                                s.visitKey,
                                true,
                                (1..3).map { PageViewIn("pv-$v-$b-$it", "https://www.boat4you.com/boat/x-$it", "/boat/x-$it", "X", "BOAT", "x-$it", Instant.now()) },
                                (1..5).map { EventIn("ev-$v-$b-$it", "CLICK", "pv-$v-$b-1", "Book online", null, null, null, false, Instant.now()) },
                            ),
                        )
                        lat += System.nanoTime() - t
                    }
                }
            }.forEach { it.get() }
        pool.shutdown()
        sampler.interrupt()
        writer.stop()
        val p95Ms = lat.sorted()[(lat.size * 0.95).toInt()] / 1_000_000.0
        repeat(3) {
            System.gc()
            Thread.sleep(150)
        }
        val heapMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / 1_048_576
        val st = writer.stats()
        println(
            "CC_LOAD rss_before_kb=$before rss_peak_kb=${peak.get()} heap_after_gc_mb=$heapMb beat_p95_ms=$p95Ms dropped=${st.dropped} failed=${st.failed}",
        )
        // context for the line above: the JVM ceiling behind the RSS delta, and how much of the flat-out burst the writer got to the database
        println("CC_LOAD_CONTEXT max_heap_mb=${Runtime.getRuntime().maxMemory() / 1_048_576} written=${st.written} queued=${st.queued}")
        p95Ms shouldBeLessThan 5.0
        (peak.get() - before) shouldBeLessThan 204_800L
        st.failed shouldBe 0L
    }
}
