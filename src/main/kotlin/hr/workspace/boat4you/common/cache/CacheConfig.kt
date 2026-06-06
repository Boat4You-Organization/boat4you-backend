package hr.workspace.boat4you.common.cache

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSeason
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRate
import org.ehcache.config.builders.CacheConfigurationBuilder
import org.ehcache.config.builders.ExpiryPolicyBuilder
import org.ehcache.config.builders.ResourcePoolsBuilder
import org.ehcache.config.units.EntryUnit
import org.ehcache.jsr107.Eh107Configuration
import org.springframework.boot.autoconfigure.cache.JCacheManagerCustomizer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.SimpleKey
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.Page
import java.time.Duration

@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManagerCustomizer(): JCacheManagerCustomizer {
        return JCacheManagerCustomizer { cacheManager ->
            val singleEntryResourcePool =
                ResourcePoolsBuilder
                    .newResourcePoolsBuilder()
                    .heap(1, EntryUnit.ENTRIES)
                    .build()
            val hundredEntryResourcePool =
                ResourcePoolsBuilder
                    .newResourcePoolsBuilder()
                    .heap(100, EntryUnit.ENTRIES)
                    .build()
            val thousandEntryResourcePool =
                ResourcePoolsBuilder
                    .newResourcePoolsBuilder()
                    .heap(1000, EntryUnit.ENTRIES)
                    .build()

            val countriesCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val locationViewsCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        Page::class.java,
                        hundredEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val countriesCountCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val locationsCountCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            // Per-region locations cache — keyed by `Region.id` (Long, not
            // SimpleKey, because the @Cacheable method takes a single
            // primitive arg). Pool sized for ~100 distinct regions across
            // all countries we sync; in practice we have ~30 today.
            val locationsCountByRegionCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        java.lang.Long::class.java,
                        List::class.java,
                        hundredEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val usedVesselTypesCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val vesselTypeYachtCountCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val manufacturersCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        Page::class.java,
                        hundredEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val equipmentCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            // F2-025: key type is `String` because the @Cacheable SpEL key
            // on OfferRepository.findAllAvailableByYacht is
            // `"#yacht.id + ':' + #statuses.hashCode()"`. Previously
            // SimpleKey wrapping a Yacht instance — broken because
            // Yacht has reference-identity hashCode (F2-017 family).
            val offersByYachtAndStatusCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        String::class.java,
                        List::class.java,
                        hundredEntryResourcePool,
                    )
                    // A8: 2-min TTL (was 10). The partner offer/availability syncs
                    // and the B2 booking flip (offer FREE->OPTION) change offer
                    // status WITHOUT going through OfferMutationService's @CacheEvict,
                    // so a 10-min window showed stale availability on the boat page.
                    // 2 min bounds the display lag to the matview refresh cadence;
                    // actual bookings are still protected by the live re-check.
                    .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(2)))
                    .build()

            val modelByExternalIdAndExternalSystem =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        Model::class.java,
                        thousandEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(60)))
                    .build()

            val modelByName =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        Model::class.java,
                        thousandEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(60)))
                    .build()

            val locationCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        java.lang.Long::class.java,
                        Location::class.java,
                        thousandEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(10)))
                    .build()

            val externalSystemResourcePool =
                ResourcePoolsBuilder
                    .newResourcePoolsBuilder()
                    .heap(2, EntryUnit.ENTRIES)
                    .build()
            val externalSystemCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        java.lang.Long::class.java,
                        ExternalSystem::class.java,
                        externalSystemResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val externalEquipmentResourcePool =
                ResourcePoolsBuilder
                    .newResourcePoolsBuilder()
                    .heap(2, EntryUnit.ENTRIES)
                    .build()
            val externalEquipmentCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        java.lang.Integer::class.java,
                        List::class.java,
                        externalEquipmentResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(10)))
                    .build()

            val extrasCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()
            val usedCharterTypesCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()
            // F2-007: key type is `Long` because the @Cacheable SpEL key
            // on YachtExtraRepository.findAllByYacht is `"#yacht.id"`.
            // Previously `Yacht::class.java` — broken because Yacht
            // has reference-identity hashCode (F2-017 family) so every
            // request loaded a fresh Yacht and the cache never hit.
            val yachtExtrasCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        java.lang.Long::class.java,
                        List::class.java,
                        thousandEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(2)))
                    .build()
            val exchangeRateForCurrency =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        String::class.java,
                        ExchangeRate::class.java,
                        hundredEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(1)))
                    .build()

            val equipmentFilter =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val extrasFilter =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            val languageCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        singleEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()
            val regionsCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        String::class.java,
                        List::class.java,
                        hundredEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()
            // Same shape as regionsCache — keyed by 2-letter countryCode, holds
            // the LocationViewDto list of marinas. 10h TTL is plenty since
            // marinas are added to the catalogue rarely; if a new one shows up
            // mid-day the admin form sees it after the cache rolls over.
            val marinasByCountryCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        String::class.java,
                        List::class.java,
                        hundredEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()
            val seasonsCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        java.lang.Long::class.java,
                        ExternalSeason::class.java,
                        thousandEntryResourcePool,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(10)))
                    .build()

            cacheManager.createCache("countriesCache", Eh107Configuration.fromEhcacheCacheConfiguration(countriesCache))
            cacheManager.createCache(
                "locationViewsCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(locationViewsCache),
            )
            cacheManager.createCache(
                "countriesCountCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(countriesCountCache),
            )
            cacheManager.createCache(
                "locationsCountCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(locationsCountCache),
            )
            cacheManager.createCache(
                "locationsCountByRegionCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(locationsCountByRegionCache),
            )
            cacheManager.createCache(
                "usedVesselTypesCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(usedVesselTypesCache),
            )
            cacheManager.createCache(
                "vesselTypeYachtCountCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(vesselTypeYachtCountCache),
            )
            cacheManager.createCache(
                "manufacturersCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(manufacturersCache),
            )
            cacheManager.createCache("equipmentCache", Eh107Configuration.fromEhcacheCacheConfiguration(equipmentCache))
            cacheManager.createCache(
                "offersByYachtAndStatusCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(offersByYachtAndStatusCache),
            )
            cacheManager.createCache(
                "modelByExternalIdAndExternalSystem",
                Eh107Configuration.fromEhcacheCacheConfiguration(modelByExternalIdAndExternalSystem),
            )
            cacheManager.createCache("modelByName", Eh107Configuration.fromEhcacheCacheConfiguration(modelByName))
            cacheManager.createCache("locationCache", Eh107Configuration.fromEhcacheCacheConfiguration(locationCache))
            cacheManager.createCache(
                "externalSystemCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(externalSystemCache),
            )
            cacheManager.createCache(
                "externalEquipmentCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(externalEquipmentCache),
            )
            cacheManager.createCache("extrasCache", Eh107Configuration.fromEhcacheCacheConfiguration(extrasCache))
            cacheManager.createCache(
                "usedCharterTypesCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(usedCharterTypesCache),
            )
            cacheManager.createCache(
                "yachtExtrasCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(yachtExtrasCache),
            )
            cacheManager.createCache(
                "exchangeRateForCurrency",
                Eh107Configuration.fromEhcacheCacheConfiguration(exchangeRateForCurrency),
            )
            cacheManager.createCache(
                "equipmentFilter",
                Eh107Configuration.fromEhcacheCacheConfiguration(equipmentFilter),
            )
            cacheManager.createCache(
                "languageCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(languageCache),
            )
            cacheManager.createCache("extrasFilter", Eh107Configuration.fromEhcacheCacheConfiguration(extrasFilter))
            cacheManager.createCache("regionsCache", Eh107Configuration.fromEhcacheCacheConfiguration(regionsCache))
            cacheManager.createCache(
                "marinasByCountryCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(marinasByCountryCache),
            )
            cacheManager.createCache("seasonsCache", Eh107Configuration.fromEhcacheCacheConfiguration(seasonsCache))
        }
    }

    /**
     * F2-005: bean is intentionally NOT `@Profile("data-sync")` gated.
     * The original commented `// @Profile("data-sync")` reflected a
     * historical assumption that external-mapping caches were used
     * only by sync jobs (which run under data-sync profile).
     *
     * In practice `externalMappingCache` / `externalMappingExtendedCache`
     * are read by `ExternalSyncService.syncYachtOffers(yachtId, ...)`
     * — the per-yacht @Async sync triggered from the public yacht
     * search endpoints in `YachtController`. That path runs on every
     * VM that serves public traffic, regardless of profile. Profile-
     * gating the customizer would leave web requests without the
     * cache definition, and Spring would throw on the first
     * `@Cacheable("externalMappingCache")` invocation.
     *
     * Renamed conceptually: the "dataSync" prefix is now misleading
     * since these caches are cross-profile. Kept the bean name for
     * compatibility with any external reference.
     */
    @Bean
    fun dataSyncCacheManagerCustomizer(): JCacheManagerCustomizer {
        return JCacheManagerCustomizer { cacheManager ->

            val resourcePools =
                ResourcePoolsBuilder
                    .newResourcePoolsBuilder()
                    .heap(20, EntryUnit.ENTRIES)
                    .build()

            val externalMappingCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        resourcePools,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(10)))
                    .build()

            val externalMappingExtendedCache =
                CacheConfigurationBuilder
                    .newCacheConfigurationBuilder(
                        SimpleKey::class.java,
                        List::class.java,
                        resourcePools,
                    ).withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(10)))
                    .build()

            cacheManager.createCache(
                "externalMappingCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(externalMappingCache),
            )
            cacheManager.createCache(
                "externalMappingExtendedCache",
                Eh107Configuration.fromEhcacheCacheConfiguration(externalMappingExtendedCache),
            )
        }
    }
}
