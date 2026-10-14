package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search caches carry the SEARCH_* switch values in their keys (DESIGN.md 1.2): `@realPriceSwitches.searchCacheKey()`
 * in a @Cacheable key resolves the bean through Spring's cache SpEL context, so a hint cached under LEGACY is never
 * served under D1. Shown on the relax hint (yachtSearchListCache uses the same expression).
 */
@SpringJUnitConfig(RealPriceSearchCacheKeyTest.Config::class)
// the switches bean's @Value TTL is injected on the returned instance as well: keep it at 0 so a flip is seen at once
@TestPropertySource(properties = ["realprice.switches.cache-ttl=PT0S"])
class RealPriceSearchCacheKeyTest {
    @Configuration
    @EnableCaching
    class Config {
        val settings = SearchSwitchesTestSupport.SearchSwitches()
        val queries = AtomicInteger()

        @Bean
        fun cacheManager(): CacheManager = ConcurrentMapCacheManager("relaxSuggestCache")

        /** Boot's converters: the switches bean's `@Value` field (a Duration) is processed on the returned instance too. */
        @Bean
        fun conversionService(): ApplicationConversionService = ApplicationConversionService()

        @Bean
        fun realPriceSwitches(): RealPriceSwitches = settings.switches

        /** Baseline count 10 (with the cabin filter), 50 without it: a hint worth surfacing (delta 40), so it is cached. */
        @Bean
        fun relax(switches: RealPriceSwitches): YachtRelaxSuggestionService {
            fun query(count: Long): Query =
                mock(Query::class.java).also {
                    `when`(it.singleResult).thenAnswer {
                        queries.incrementAndGet()
                        count
                    }
                }
            val withCabins = query(10)
            val withoutCabins = query(50)
            val entityManager = mock(EntityManager::class.java)
            `when`(entityManager.createNativeQuery(anyString())).thenAnswer { inv ->
                if ("cabins" in (inv.arguments[0] as String)) withCabins else withoutCabins
            }
            return YachtRelaxSuggestionService(
                entityManager,
                mock(LocationRepository::class.java),
                mock(CountryRepository::class.java),
                mock(RegionRepository::class.java),
                switches,
            )
        }
    }

    @Autowired
    private lateinit var config: Config

    @Autowired
    private lateinit var relax: YachtRelaxSuggestionService

    @Autowired
    private lateinit var cacheManager: CacheManager

    @Test
    fun `a switch flip is a different cache key`() {
        val filters = YachtRelaxSuggestionService.ActiveFilters(minCabins = 3)
        assertEquals(40L, relax.suggest(filters)?.delta)
        assertEquals(40L, relax.suggest(filters)?.delta)
        assertEquals(2, config.queries.get(), "the second call is a cache hit")

        config.settings.set(SettingsKeyEnum.SEARCH_PREDICATE, "D1")
        assertEquals(40L, relax.suggest(filters)?.delta)
        assertEquals(4, config.queries.get(), "under D1 the LEGACY entry is not served")

        val keys = (cacheManager.getCache("relaxSuggestCache") as ConcurrentMapCache).nativeCache.keys.map { it.toString() }
        assertEquals(2, keys.size, "$keys")
        assertTrue(keys.any { it.endsWith("rp:LEGACY:OFF:true") }, "$keys")
        assertTrue(keys.any { it.endsWith("rp:D1:OFF:true") }, "$keys")
    }
}
