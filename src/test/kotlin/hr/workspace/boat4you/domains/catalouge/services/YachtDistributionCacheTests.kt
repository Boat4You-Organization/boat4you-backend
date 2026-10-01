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
import org.ehcache.jsr107.EhcacheCachingProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.jcache.JCacheCacheManager
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.net.URLClassLoader
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.cache.Caching
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import javax.cache.CacheManager as JCacheManager

/**
 * 1.10.2026 (Codex audit F2): a burst of cold landing pages ran the 9-11 distribution scans once
 * PER concurrent request (`@Cacheable` without `sync`), which is how 22 Hikari connections ended up
 * in `getDistribution`. Real Spring caching proxy + the real Ehcache JCache provider configured by
 * the production [CacheConfig], so these prove the provider honours `sync = true`, that a shed
 * request reaches the controller as itself (→ 503) and is not cached, and the per-key TTL.
 */
class YachtDistributionCacheTests {
    @Configuration
    @EnableCaching
    class CachingOn

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

    private lateinit var jcache: JCacheManager
    private lateinit var context: AnnotationConfigApplicationContext
    private val runner = CountingRunner()
    private lateinit var service: YachtDistributionService

    @BeforeEach
    fun setUp() {
        val provider = Caching.getCachingProvider(EhcacheCachingProvider::class.java.name)
        // Own class loader = own CacheManager (Ehcache reads a non-default URI as an XML config URL),
        // so nothing is shared with other tests in this JVM.
        jcache = provider.getCacheManager(provider.defaultURI, URLClassLoader(arrayOf(), javaClass.classLoader))
        CacheConfig().cacheManagerCustomizer().customize(jcache)
        context =
            AnnotationConfigApplicationContext().apply {
                register(CachingOn::class.java)
                registerBean("cacheManager", CacheManager::class.java, Supplier { JCacheCacheManager(jcache) })
                registerBean(
                    YachtDistributionService::class.java,
                    Supplier {
                        YachtDistributionService(
                            mock(EntityManager::class.java),
                            mock(LocationRepository::class.java),
                            mock(CountryRepository::class.java),
                            mock(RegionRepository::class.java),
                            runner,
                        )
                    },
                )
                refresh()
            }
        service = context.getBean(YachtDistributionService::class.java)
    }

    @AfterEach
    fun tearDown() {
        context.close()
        jcache.close()
    }

    @Test
    fun `concurrent misses on one key run the query once and share the result`() {
        val callers = 8
        runner.hold = CountDownLatch(1)
        val results = arrayOfNulls<YachtDistributionDto>(callers)
        val threads =
            (0 until callers).map { i ->
                thread { results[i] = service.getDistribution(locationIds = listOf("c-54")) }
            }

        // Wait until every caller is parked: one inside the (held) query, the rest on the cache
        // entry. Without sync all 8 would be inside the query by now.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            threads.any { it.state !in setOf(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING) }
        ) {
            Thread.sleep(5)
        }
        Thread.sleep(200)
        runner.hold!!.countDown()
        threads.forEach { it.join(20_000) }

        assertEquals(1, runner.calls.get(), "concurrent misses on one key must run the scans once")
        results.forEach { assertSame(results[0], it) }
        // …and the next request is a plain hit.
        service.getDistribution(locationIds = listOf("c-54"))
        assertEquals(1, runner.calls.get())
    }

    @Test
    fun `a shed request reaches the caller as itself and is not cached`() {
        runner.failWith = HeavyQueryBusyException(HeavyQuery.DISTRIBUTION, Reason.SATURATED)

        // Not wrapped by the cache provider: ApiErrorHandler maps exactly this type to 503.
        val shed = assertFailsWith<HeavyQueryBusyException> { service.getDistribution(locationIds = listOf("r-5")) }
        assertEquals(Reason.SATURATED, shed.reason)

        runner.failWith = null
        service.getDistribution(locationIds = listOf("r-5"))
        assertEquals(2, runner.calls.get(), "the failed attempt must not have left a cache entry")
    }

    @Test
    fun `landing keys without dates live 30 minutes, dated keys 3`() {
        service.getDistribution(locationIds = listOf("c-54"))
        service.getDistribution(
            locationIds = listOf("c-54"),
            startDate = LocalDate.of(2027, 7, 3),
            endDate = LocalDate.of(2027, 7, 10),
        )
        service.getDistribution(locationIds = listOf("c-54"), startDate = LocalDate.of(2027, 7, 3))

        val keys = jcache.getCache("facetDistributionCache", String::class.java, YachtDistributionDto::class.java).map { it.key }.sorted()

        assertEquals(3, keys.size, "three different filter sets are three entries: $keys")
        val ttls = keys.associateWith { FacetDistributionExpiry.ttlFor(it) }
        assertEquals(1, ttls.values.count { it == Duration.ofMinutes(30) }, "only the undated key is long-lived: $ttls")
        assertEquals(2, ttls.values.count { it == Duration.ofMinutes(3) }, "any date keeps the short TTL: $ttls")
        assertTrue(keys.single { ttls[it] == Duration.ofMinutes(30) }.startsWith(FacetDistributionExpiry.UNDATED_KEY_PREFIX))
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
