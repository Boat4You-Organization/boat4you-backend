package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.cache.CacheConfig
import hr.workspace.boat4you.common.cache.FacetDistributionExpiry
import hr.workspace.boat4you.domains.catalouge.dto.YachtDistributionDto
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException
import hr.workspace.boat4you.domains.catalouge.exceptions.HeavyQueryBusyException.Reason
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import jakarta.persistence.EntityManager
import org.ehcache.core.config.DefaultConfiguration
import org.ehcache.core.spi.time.TimeSource
import org.ehcache.impl.internal.TimeSourceConfiguration
import org.ehcache.jsr107.EhcacheCachingProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.springframework.cache.jcache.JCacheCacheManager
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.cache.Caching
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import javax.cache.CacheManager as JCacheManager

/**
 * 1.10.2026 (Codex audit F2 + its review): a burst of cold landing pages ran the 9-11 distribution
 * scans once PER concurrent request, which is how 22 Hikari connections ended up in
 * `getDistribution`; and the `@Cacheable(sync = true)` fix then ran the gate wait and the query
 * under Ehcache's bin lock. The service caches by hand now. Real Ehcache JCache configured by the
 * production [CacheConfig], with a test clock, so these prove: one query per key however many
 * concurrent misses, a shed leader fails its followers at once, a hit never waits for a running
 * miss, and Ehcache really expires landing keys after 30 min and search keys after 3.
 */
class YachtDistributionCacheTests {
    /** Stands in for HeavyQueryGuard: counts the misses that reach the DB side, optionally blocks or fails. */
    private class CountingRunner : HeavyQueryRunner {
        val calls = AtomicInteger()

        @Volatile var hold: CountDownLatch? = null

        @Volatile var failWith: RuntimeException? = null

        override fun <T> read(
            query: HeavyQuery,
            block: () -> T,
        ): T {
            calls.incrementAndGet()
            hold?.await(20, TimeUnit.SECONDS)
            failWith?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return distribution() as T
        }
    }

    /** Ehcache reads expiry time from this clock, so a test can move 30 minutes on. */
    private class TestClock : TimeSource {
        private val nowMs = AtomicLong(1_000_000L)

        override fun getTimeMillis(): Long = nowMs.get()

        fun advance(duration: Duration) {
            nowMs.addAndGet(duration.toMillis())
        }
    }

    private lateinit var jcache: JCacheManager
    private val clock = TestClock()
    private val runner = CountingRunner()
    private lateinit var service: YachtDistributionService

    @BeforeEach
    fun setUp() {
        val provider = Caching.getCachingProvider(EhcacheCachingProvider::class.java.name) as EhcacheCachingProvider
        // Own URI = own CacheManager, so nothing is shared with other tests in this JVM.
        jcache =
            provider.getCacheManager(
                URI.create("urn:facet-distribution-test:${UUID.randomUUID()}"),
                DefaultConfiguration(javaClass.classLoader, TimeSourceConfiguration(clock)),
            )
        CacheConfig().cacheManagerCustomizer().customize(jcache)
        service =
            YachtDistributionService(
                mock(EntityManager::class.java),
                mock(LocationRepository::class.java),
                mock(CountryRepository::class.java),
                mock(RegionRepository::class.java),
                runner,
                JCacheCacheManager(jcache).apply { afterPropertiesSet() },
            )
    }

    @AfterEach
    fun tearDown() {
        jcache.close()
    }

    /** Starts [callers] threads, waits until all are parked (one in the held query, the rest on it), then releases the query. */
    private fun <R> concurrently(
        callers: Int,
        call: () -> R,
    ): List<Result<R>> {
        runner.hold = CountDownLatch(1)
        val results = arrayOfNulls<Result<R>>(callers)
        val threads = (0 until callers).map { i -> thread { results[i] = runCatching(call) } }
        val parked = setOf(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && threads.any { it.state !in parked }) {
            Thread.sleep(5)
        }
        Thread.sleep(200)
        runner.hold!!.countDown()
        threads.forEach { it.join(20_000) }
        runner.hold = null
        return results.map { it!! }
    }

    @Test
    fun `concurrent misses on one key run the query once and share the result`() {
        val results = concurrently(8) { service.getDistribution(locationIds = listOf("c-54")) }

        assertEquals(1, runner.calls.get(), "concurrent misses on one key must run the scans once")
        results.forEach { assertSame(results[0].getOrThrow(), it.getOrThrow()) }
        // …and the next request is a plain hit.
        service.getDistribution(locationIds = listOf("c-54"))
        assertEquals(1, runner.calls.get())
    }

    @Test
    fun `a shed request reaches the caller as itself and is not cached`() {
        runner.failWith = HeavyQueryBusyException(HeavyQuery.DISTRIBUTION, Reason.SATURATED)

        // Not wrapped: ApiErrorHandler maps exactly this type to 503.
        val shed = assertFailsWith<HeavyQueryBusyException> { service.getDistribution(locationIds = listOf("r-5")) }
        assertEquals(Reason.SATURATED, shed.reason)

        runner.failWith = null
        service.getDistribution(locationIds = listOf("r-5"))
        assertEquals(2, runner.calls.get(), "the failed attempt must not have left a cache entry")
    }

    @Test
    fun `a shed miss fails its waiting followers at once, not one gate wait after another`() {
        runner.failWith = HeavyQueryBusyException(HeavyQuery.DISTRIBUTION, Reason.SATURATED)

        val startedAt = System.nanoTime()
        val results = concurrently(6) { service.getDistribution(locationIds = listOf("r-5")) }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(1, runner.calls.get(), "the followers must take the leader's answer, not queue for the gate themselves")
        results.forEach { assertIs<HeavyQueryBusyException>(it.exceptionOrNull(), "every follower answers 503") }
        assertTrue(tookMs < 5_000, "followers were refused one after another ($tookMs ms)")

        runner.failWith = null
        service.getDistribution(locationIds = listOf("r-5"))
        assertEquals(2, runner.calls.get(), "nothing was cached for the shed key")
    }

    @Test
    fun `a hit never waits for a running miss of another key`() {
        service.getDistribution(locationIds = listOf("l-9001"))
        assertEquals(1, runner.calls.get())

        runner.hold = CountDownLatch(1)
        val miss = thread { service.getDistribution(locationIds = listOf("c-54")) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (runner.calls.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }

        val startedAt = System.nanoTime()
        service.getDistribution(locationIds = listOf("l-9001"))
        val hitMs = (System.nanoTime() - startedAt) / 1_000_000

        runner.hold!!.countDown()
        miss.join(10_000)
        assertTrue(hitMs < 1_000, "the hit waited $hitMs ms for another key's miss")
        assertEquals(2, runner.calls.get())
    }

    @Test
    fun `Ehcache expires landing keys after 30 minutes and search keys after 3`() {
        val landing = { service.getDistribution(locationIds = listOf("c-54"), vesselTypes = null) }
        val dated = { service.getDistribution(locationIds = listOf("c-54"), startDate = LocalDate.of(2027, 7, 3), endDate = LocalDate.of(2027, 7, 10)) }
        val slider = { service.getDistribution(locationIds = listOf("c-54"), minPriceWeekly = BigDecimal(3000)) }

        landing()
        dated()
        slider()
        assertEquals(3, runner.calls.get(), "three filter sets are three entries")
        val keys = jcache.getCache("facetDistributionCache", String::class.java, YachtDistributionDto::class.java).map { it.key }
        assertEquals(1, keys.count { it.startsWith(FacetDistributionExpiry.LANDING_KEY_PREFIX) }, "only the landing is long-lived: $keys")
        assertEquals(2, keys.count { it.startsWith(FacetDistributionExpiry.SEARCH_KEY_PREFIX) }, "a date or a slider is a search: $keys")

        clock.advance(Duration.ofMinutes(3).minusSeconds(1))
        landing()
        dated()
        slider()
        assertEquals(3, runner.calls.get(), "all three are still hits just under 3 min")

        clock.advance(Duration.ofSeconds(2))
        landing()
        assertEquals(3, runner.calls.get(), "the landing outlives 3 min")
        dated()
        slider()
        assertEquals(5, runner.calls.get(), "the dated and the slider entries expired after 3 min")

        clock.advance(Duration.ofMinutes(27).minusSeconds(2))
        landing()
        assertEquals(5, runner.calls.get(), "the landing is still a hit just under 30 min (a hit does not extend it)")

        clock.advance(Duration.ofSeconds(2))
        landing()
        assertEquals(6, runner.calls.get(), "the landing expired after 30 min")
        assertEquals(Duration.ofMinutes(3), FacetDistributionExpiry.ttlFor("[[c-54], null, …]"), "unknown key shapes stay short")
    }

    companion object {
        private fun distribution() =
            YachtDistributionDto(
                priceHistogram = listOf(1L),
                priceMedian = BigDecimal.ONE,
                priceMin = BigDecimal(500),
                priceMax = BigDecimal(200_000),
                maxDiscountPerc = null,
                lengthHistogram = emptyList(),
                engineHistogram = emptyList(),
                byVesselType = emptyMap(),
                byCharterType = emptyMap(),
                byMainsailType = emptyMap(),
                byCabins = emptyMap(),
                byManufacturer = emptyMap(),
                byModel = emptyMap(),
                byAmenity = emptyMap(),
            )
    }
}
