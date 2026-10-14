package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.domains.catalouge.controllers.YachtController
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentAliases
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageImpl
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Re-audit R12 (14.10.2026) through the real `@Cacheable` key of YachtQueryingService.getYachts: junk `sortBy` values and
 * out-of-range `size` values are normalised by the controller, so they hit the entry the plain listing created instead
 * of each running a cold full listing query. The search body is a counting stub behind Spring's cache proxy.
 */
@SpringJUnitConfig(SearchListCacheKeyTest.Config::class)
@TestPropertySource(properties = ["realprice.switches.cache-ttl=PT0S"])
class SearchListCacheKeyTest {
    @Configuration
    @EnableCaching
    class Config {
        val executions = AtomicInteger()

        @Suppress("UNCHECKED_CAST")
        private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

        @Bean
        fun cacheManager(): CacheManager = ConcurrentMapCacheManager("yachtSearchListCache")

        /** Boot's converters: the switches bean's `@Value` field (a Duration) is processed on the returned instance too. */
        @Bean
        fun conversionService(): ApplicationConversionService = ApplicationConversionService()

        @Bean
        fun realPriceSwitches(): RealPriceSwitches = RealPriceSwitchesFixture.switches()

        @Bean
        fun yachtQueryingService(): YachtQueryingService =
            mock(YachtQueryingService::class.java).also { service ->
                `when`(service.getYachts(any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                    executions.incrementAndGet()
                    PageImpl(emptyList<Any>())
                }
            }

        @Bean
        fun yachtController(
            yachtQueryingService: YachtQueryingService,
            realPriceSwitches: RealPriceSwitches,
        ): YachtController =
            YachtController(
                yachtQueryingService,
                mock(OfferQueryingService::class.java),
                mock(ExternalSyncService::class.java),
                mock(UserRepository::class.java),
                mock(YachtTwinCanonicalService::class.java),
                mock(EquipmentAliases::class.java),
                realPriceSwitches,
            )
    }

    @Autowired
    private lateinit var config: Config

    @Autowired
    private lateinit var controller: YachtController

    @Autowired
    private lateinit var cacheManager: CacheManager

    @BeforeEach
    fun reset() {
        SecurityContextHolder.clearContext()
        cacheManager.getCache("yachtSearchListCache")!!.clear()
        config.executions.set(0)
    }

    private fun search(
        sortBy: String?,
        size: Int,
    ) = controller.getYachts(
        locations = null,
        charterType = null,
        vesselType = null,
        manufacturer = null,
        model = null,
        mainSailType = null,
        minBuildYear = null,
        maxBuildYear = null,
        minPersons = null,
        maxPersons = null,
        minCabins = null,
        maxCabins = null,
        minBerths = null,
        maxBerths = null,
        minLength = null,
        maxLength = null,
        minPrice = null,
        maxPrice = null,
        startDate = null,
        endDate = null,
        minWc = null,
        maxWc = null,
        minEnginePower = null,
        maxEnginePower = null,
        curr = null,
        amenities = null,
        services = null,
        sortBy = sortBy,
        yachtIds = null,
        agencyIds = null,
        includeUnavailable = false,
        countryCodes = null,
        page = 0,
        size = size,
        lang = null,
    )

    @Test
    fun `junk sortBy values share the plain listing's cache entry`() {
        search(null, 7)
        search("zzz1", 7)
        search("zzz2", 7)
        search("recommended", 7)
        search("", 7)
        assertEquals(1, config.executions.get(), "one query execution for five junk/no-op sorts")

        search("lengthAsc", 7)
        assertEquals(2, config.executions.get(), "a real sort is its own entry")
    }

    @Test
    fun `sizes above the page cap share the size=100 entry`() {
        search(null, 100)
        search(null, 101)
        search(null, 5000)
        assertEquals(1, config.executions.get())
        search(null, 0)
        search(null, -5)
        search(null, 1)
        assertEquals(2, config.executions.get(), "size <= 0 shares the size=1 entry")
    }
}
