package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.cache.CacheConfig
import io.kotest.matchers.shouldBe
import org.ehcache.core.config.DefaultConfiguration
import org.ehcache.core.spi.time.TimeSource
import org.ehcache.impl.internal.TimeSourceConfiguration
import org.ehcache.jsr107.EhcacheCachingProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.jcache.JCacheCacheManager
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import javax.cache.CacheManager as JCacheManager

/**
 * 'modelDonorCache' as production configures it (CacheConfig, real Ehcache JCache behind Spring's @Cacheable proxy):
 * one donor query per model and country, "no donor" cached as well, and a fresh query after the 10-minute TTL.
 */
class ModelDonorCacheTest {
    @Configuration
    @EnableCaching
    open class CachingOn

    /** Answers no candidate rows (= no donor) and counts the donor queries. */
    private class CountingJdbc : JdbcTemplate() {
        var queries = 0

        override fun <T : Any?> query(
            sql: String,
            rowMapper: RowMapper<T>,
            vararg args: Any?,
        ): MutableList<T> {
            queries++
            return mutableListOf()
        }
    }

    private class TestClock : TimeSource {
        private val nowMs = AtomicLong(1_000_000L)

        override fun getTimeMillis(): Long = nowMs.get()

        fun advance(duration: Duration) {
            nowMs.addAndGet(duration.toMillis())
        }
    }

    private val clock = TestClock()
    private val jdbc = CountingJdbc()
    private lateinit var jcache: JCacheManager
    private lateinit var context: AnnotationConfigApplicationContext

    @BeforeEach
    fun setUp() {
        val provider = javax.cache.Caching.getCachingProvider(EhcacheCachingProvider::class.java.name) as EhcacheCachingProvider
        jcache =
            provider.getCacheManager(
                URI.create("urn:model-donor-test:${UUID.randomUUID()}"),
                DefaultConfiguration(javaClass.classLoader, TimeSourceConfiguration(clock)),
            )
        CacheConfig().cacheManagerCustomizer().customize(jcache)
        context =
            AnnotationConfigApplicationContext().apply {
                register(CachingOn::class.java)
                registerBean(CacheManager::class.java, Supplier { JCacheCacheManager(jcache) })
                registerBean(ModelDonorLookup::class.java, Supplier { ModelDonorLookup(jdbc) })
                refresh()
            }
    }

    @AfterEach
    fun tearDown() {
        context.close()
        jcache.close()
    }

    @Test
    fun `one query per model and country, no donor cached too, again after 10 minutes`() {
        val lookup = context.getBean(ModelDonorLookup::class.java)

        repeat(3) { lookup.donorFor(812L, "HR") shouldBe ModelDonor(null) }
        jdbc.queries shouldBe 1

        lookup.donorFor(812L, "GR")
        lookup.donorFor(812L, null)
        lookup.donorFor(813L, "HR")
        jdbc.queries shouldBe 4

        clock.advance(Duration.ofMinutes(9))
        lookup.donorFor(812L, "HR")
        jdbc.queries shouldBe 4

        clock.advance(Duration.ofMinutes(2))
        lookup.donorFor(812L, "HR")
        jdbc.queries shouldBe 5
    }
}
