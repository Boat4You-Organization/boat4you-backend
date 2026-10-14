package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.MatchKind
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtSearchSelectResult
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.CUSTOM
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.OPTION_BOAT
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.PLACEHOLDER
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.Q
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.REQUESTS
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.Request
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.S
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.T
import hr.workspace.boat4you.domains.catalouge.services.RealPriceSearchFixture.W
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRateRepository
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.core.io.ClassPathResource
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-price search rule (Mario 26.9.2026, decisions 14.10.2026; DESIGN.md WS-A tests 1-12) end to end on the real
 * R__1_03 matview: the listing page, its count, the sidebar facets and the relax baseline under the runtime switches
 * SEARCH_PREDICATE (LEGACY / D1), SEARCH_PRICE_FLOOR (OFF / ON) and SEARCH_DEMOTE_STALE_OPTIONS (true / false).
 *
 * With every switch at its default the answers are exactly those of the code before WS-A (golden file captured on the
 * base commit a9577a1 with the same [RealPriceSearchFixture]).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchRealPriceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val GOLDEN = "realprice/legacy-golden.txt"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
    private val yachtMapper: YachtMapper = mock(YachtMapper::class.java)
    private val settings = SearchSwitchesTestSupport.SearchSwitches()
    private lateinit var service: YachtQueryingService
    private lateinit var distribution: YachtDistributionService
    private lateinit var relax: YachtRelaxSuggestionService

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        RealPriceSearchFixture.create(JdbcTemplate(dataSource))

        entityManagerFactory = buildEntityManagerFactory()
        entityManager = entityManagerFactory.createEntityManager()
        val repositories = JpaRepositoryFactory(entityManager)
        val locations = repositories.getRepository(LocationRepository::class.java)
        val regions = repositories.getRepository(RegionRepository::class.java)
        val countries = repositories.getRepository(CountryRepository::class.java)
        service =
            YachtQueryingService(
                entityManager,
                mock(YachtRepository::class.java),
                locations,
                mock(ExternalReservationRepository::class.java),
                yachtMapper,
                mock(OfferRepository::class.java),
                mock(CustomYachtViewRepository::class.java),
                mock(CustomYachtDetailRepository::class.java),
                mock(YachtTranslationRepository::class.java),
                mock(OfferMapper::class.java),
                mock(FileSystemService::class.java),
                ExchangeRateCalculationService(mock(ExchangeRateRepository::class.java)),
                mock(YachtExtraRepository::class.java),
                mock(ExternalBaseRepository::class.java),
                regions,
                countries,
                PassThroughHeavyQueries,
                settings.switches,
                SearchSwitchesTestSupport.liveOptionLookup(),
            )
        distribution =
            YachtDistributionService(entityManager, locations, countries, regions, PassThroughHeavyQueries, NoOpCacheManager(), settings.switches)
        relax = YachtRelaxSuggestionService(entityManager, locations, countries, regions, settings.switches)
    }

    @AfterAll
    fun tearDown() {
        entityManager.close()
        entityManagerFactory.close()
        dataSource.close()
    }

    @BeforeEach
    fun defaults() {
        settings.reset()
    }

    private fun buildEntityManagerFactory(): EntityManagerFactory {
        val factoryBean = LocalContainerEntityManagerFactoryBean()
        factoryBean.dataSource = dataSource
        factoryBean.setPackagesToScan("hr.workspace.boat4you")
        factoryBean.jpaVendorAdapter = HibernateJpaVendorAdapter()
        factoryBean.setJpaPropertyMap(
            mapOf(
                "hibernate.hbm2ddl.auto" to "none",
                "hibernate.physical_naming_strategy" to CamelCaseToUnderscoresNamingStrategy::class.java.name,
                "hibernate.implicit_naming_strategy" to SpringImplicitNamingStrategy::class.java.name,
            ),
        )
        factoryBean.afterPropertiesSet()
        return factoryBean.`object`!!
    }

    // ---- harness ----

    private class Answer(
        val count: Long,
        /** The arguments of every YachtMapper.toDto call, in page order: [0] the row, [3] isOption, [6] match kind. */
        val calls: List<List<Any?>>,
    ) {
        val rows: List<YachtSearchSelectResult> get() = calls.map { it[0] as YachtSearchSelectResult }
        val ids: List<Long> get() = rows.map { it.id }

        fun card(id: Long): YachtSearchSelectResult = rows.single { it.id == id }

        fun call(id: Long): List<Any?> = calls.single { (it[0] as YachtSearchSelectResult).id == id }
    }

    private fun search(
        r: Request,
        legacyAvailability: Boolean = false,
    ): Answer {
        clearInvocations(yachtMapper)
        val params = RealPriceSearchFixture.params(r).copy(legacyAvailability = legacyAvailability)
        val page = service.getYachts(params, r.sortBy, LanguageEnum.EN, 0, 100, isAdmin = false)
        val calls = mockingDetails(yachtMapper).invocations.map { it.arguments.toList() }
        assertEquals(page.content.size, calls.size)
        return Answer(page.totalElements, calls)
    }

    private fun canonical(r: Request): String {
        val answer = search(r)
        val facets = if (r.facetable) RealPriceSearchFixture.distribution(distribution, r) else null
        val baseline = if (r.facetable) relax.baselineCount(RealPriceSearchFixture.relaxFilters(r)) else null
        return RealPriceSearchFixture.canonical(r, answer.count, answer.calls, facets, baseline)
    }

    private fun total(row: YachtSearchSelectResult): BigDecimal? = row.numberOfDays?.let { row.clientPrice?.multiply(BigDecimal(it))?.setScale(2, RoundingMode.HALF_UP) }

    /** The boat page's "has a bookable future offer" (private in the service: one test, read reflectively). */
    private fun hasBookableFutureOffer(id: Long): Boolean =
        YachtQueryingService::class.java
            .getDeclaredMethod("hasBookableFutureOffer", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, id) as Boolean

    private fun request(name: String): Request = REQUESTS.single { it.name == name }

    private fun d1(floor: Boolean = true) = settings.search(predicate = "D1", floor = if (floor) "ON" else "OFF")

    /**
     * Page == count == facet total for every request; == relax baseline as well (the requests use only filters the
     * relax hint supports: destination, dates, charter type, price ceiling).
     */
    private fun assertParity(
        r: Request,
        answer: Answer,
    ) {
        assertEquals(answer.count, answer.calls.size.toLong(), "${r.name}: page == count")
        if (!r.facetable) return
        val facets = RealPriceSearchFixture.distribution(distribution, r)
        assertEquals(answer.count, facets.byVesselType.values.sum(), "${r.name}: count == facet total")
        assertEquals(answer.count, relax.baselineCount(RealPriceSearchFixture.relaxFilters(r)), "${r.name}: count == relax baseline")
    }

    private fun Answer.assertCard(
        id: Long,
        from: LocalDate,
        nights: Int,
        total: String,
        match: MatchKind,
    ) {
        val row = card(id)
        assertEquals(from, row.offerDateFrom, "yacht $id from")
        assertEquals(from.plusDays(nights.toLong()), row.offerDateTo, "yacht $id to")
        assertEquals(nights, row.numberOfDays, "yacht $id nights")
        assertEquals(BigDecimal(total).setScale(2), total(row), "yacht $id total")
        assertEquals(match, call(id)[6], "yacht $id match kind")
    }

    // ---- 12: LEGACY = today ----

    @Test
    fun `12 - every switch at its default answers exactly as before WS-A (golden from a9577a1)`() {
        val golden = ClassPathResource(GOLDEN).inputStream.bufferedReader().readText()
        val now = REQUESTS.joinToString("") { canonical(it) }
        assertEquals(golden, now)
        // explicit default rows read the same as no rows
        settings.search(predicate = "LEGACY", floor = "OFF", demoteStaleOptions = "true")
        assertEquals(golden, REQUESTS.joinToString("") { canonical(it) })
    }

    // ---- 1, 2, 3, 5, 7: a 7-night Saturday search ----

    @Test
    fun `1-3, 5, 7 - D1 7 nights - exact, shifted within 3 days, never longer or shorter, taken only on evidence`() {
        d1()
        listOf("S 7n", "S 7n asc", "S 7n desc", "S 7n did l-1", "S 7n region").forEach { name ->
            val r = request(name)
            val answer = search(r)
            // 103 (-4 days), 104 (14/21/28 nights), 105 (3/10 nights), 106 (ext 6), 107 (strike), 108 (RESERVED overlap),
            // 109 (partner_period_check hide), 113 (hull), 130 (placeholder) are absent
            assertEquals(setOf(101L, 102L, 111L, 112L, 114L, 131L, CUSTOM, OPTION_BOAT), answer.ids.toSet(), name)
            assertParity(r, answer)
        }
        val answer = search(request("S 7n asc"))
        answer.assertCard(101, S, 7, "2100", MatchKind.EXACT)
        answer.assertCard(102, S.minusDays(2), 7, "1600", MatchKind.SHIFTED)
        answer.assertCard(111, S, 7, "2400", MatchKind.EXACT)
        answer.assertCard(112, S, 7, "2500", MatchKind.EXACT)
        answer.assertCard(114, S.plusDays(3), 7, "2700", MatchKind.SHIFTED)
        answer.assertCard(131, S, 7, "300", MatchKind.EXACT)
        assertNull(answer.card(CUSTOM).clientPrice)
        assertEquals(listOf(131L, 102L, 101L, 111L, 112L, 114L, OPTION_BOAT, CUSTOM), answer.ids)
    }

    @Test
    fun `LEGACY lists the longer blocks, the shifted -4 row, the hull-blocked row and the placeholder (today)`() {
        val ids = search(request("S 7n")).ids.toSet()
        assertTrue(ids.containsAll(listOf(103L, 104L, 105L, 106L, 107L, 108L, 113L, PLACEHOLDER)), "$ids")
    }

    // ---- 4: cancelled-reservation shape, Wednesday +-3 ----

    @Test
    fun `4 - Wed-Wed - an exact UNAVAILABLE row without evidence does not hide the boat, a Sat row 4 days off never qualifies`() {
        d1()
        val r = request("W 7n Wed")
        val answer = search(r)
        assertEquals(setOf(110L, 116L, CUSTOM), answer.ids.toSet())
        answer.assertCard(110, W.plusDays(3), 7, "2000", MatchKind.SHIFTED)
        answer.assertCard(116, W, 7, "1900", MatchKind.EXACT)
        assertParity(r, answer)
    }

    // ---- 6: tiling ----

    @Test
    fun `6 - 14 nights Sat-Sat tiled by priced weeks - one row per week, the period total, price sort on it`() {
        d1()
        val r = request("T 14n asc")
        val answer = search(r)
        // 123 (week 2 missing) is absent; 120 is never listed through its 14-night OPTION starting a week early
        assertEquals(listOf(122L, 125L, 127L, 124L, 121L, 120L, CUSTOM), answer.ids)
        answer.assertCard(120, T, 14, "14778.89", MatchKind.EXACT)
        // gulet with BAREBOAT + CREWED rows: not doubled
        answer.assertCard(121, T, 14, "11000", MatchKind.EXACT)
        // two routes in week 2: the cheaper row
        answer.assertCard(122, T, 14, "4900", MatchKind.EXACT)
        // an exact 14-night row beats the cheaper tiling
        answer.assertCard(124, T, 14, "9000", MatchKind.EXACT)
        answer.assertCard(125, T, 14, "5700", MatchKind.EXACT)
        answer.assertCard(127, T, 14, "6000", MatchKind.EXACT)
        assertParity(r, answer)
        val desc = search(request("T 14n desc"))
        assertEquals(listOf(120L, 121L, 124L, 127L, 125L, 122L, CUSTOM), desc.ids)
        assertParity(request("T 14n desc"), desc)
    }

    @Test
    fun `6 - a tiling must pass the page's own row filters - destination, charter type, price`() {
        d1()
        val did = request("T 14n did l-1")
        val byDid = search(did)
        assertFalse(125L in byDid.ids, "week 2 only from a base outside the destination")
        assertParity(did, byDid)

        val bareboat = request("T 14n bareboat")
        val byBareboat = search(bareboat)
        assertEquals(listOf(122L, 125L, 127L, 124L, 120L, CUSTOM), byBareboat.ids, "a gulet is never bareboat")
        assertParity(bareboat, byBareboat)

        val crewed = request("T 14n crewed")
        val byCrewed = search(crewed)
        assertEquals(listOf(121L), byCrewed.ids)
        byCrewed.assertCard(121, T, 14, "11000", MatchKind.EXACT)
        assertParity(crewed, byCrewed)

        val max = request("T 14n max 3000")
        val byMax = search(max)
        assertEquals(listOf(122L, 125L), byMax.ids, "127's week 2 is above the ceiling")
        assertParity(max, byMax)
    }

    // ---- 9: N = 3 ----

    @Test
    fun `9 - 3 nights - shifted by 3 days is absent, by 2 days present`() {
        d1()
        val r = request("Q 3n")
        val answer = search(r)
        assertEquals(setOf(141L, CUSTOM), answer.ids.toSet())
        answer.assertCard(141, Q.plusDays(2), 3, "950", MatchKind.SHIFTED)
        assertParity(r, answer)
    }

    // ---- 8: D5 floor ----

    @Test
    fun `8 - placeholder-priced boat - absent dated, undated, count and facets, kept in sitemap shards`() {
        d1()
        listOf("S 7n", "undated", "undated asc", "undated weekly").forEach { name ->
            val r = request(name)
            val answer = search(r)
            assertFalse(PLACEHOLDER in answer.ids, name)
            assertTrue(131L in answer.ids, "$name: exactly 300.00 EUR a week is a real price")
            assertTrue(CUSTOM in answer.ids, "$name: a custom yacht without a low price stays")
            assertParity(r, answer)
        }
        assertEquals(28L, search(request("undated")).count)
        val shard = search(request("sitemap shard"))
        assertTrue(PLACEHOLDER in shard.ids, "Mario 7.10.2026: sitemaps are not reduced")
        assertEquals(29L, shard.count)
        assertFalse(hasBookableFutureOffer(PLACEHOLDER))
        assertTrue(hasBookableFutureOffer(131L))
        assertTrue(hasBookableFutureOffer(CUSTOM))

        // floor OFF: the placeholder is back (dated: "price on request"), and its boat page is bookable as today
        d1(floor = false)
        val withoutFloor = search(request("S 7n asc"))
        assertTrue(PLACEHOLDER in withoutFloor.ids)
        assertNull(withoutFloor.card(PLACEHOLDER).clientPrice)
        assertEquals(listOf(131L, 102L, 101L, 111L, 112L, 114L, OPTION_BOAT, PLACEHOLDER, CUSTOM), withoutFloor.ids)
        assertParity(request("S 7n asc"), withoutFloor)
        assertEquals(29L, search(request("undated")).count)
        assertTrue(hasBookableFutureOffer(PLACEHOLDER))
    }

    // ---- 10: admin ----

    @Test
    fun `10 - an admin caller keeps the LEGACY predicate and no floor`() {
        val names = listOf("S 7n", "T 14n asc", "undated", "W 7n Wed")
        val legacy = names.associateWith { search(request(it), legacyAvailability = true).calls.map { c -> c.take(12) } }
        d1()
        names.forEach { name ->
            val admin = search(request(name), legacyAvailability = true)
            assertEquals(legacy.getValue(name), admin.calls.map { it.take(12) }, name)
        }
        assertTrue(PLACEHOLDER in search(request("S 7n"), legacyAvailability = true).ids)
    }

    // ---- 11: A2 ----

    @Test
    fun `11 - SEARCH_DEMOTE_STALE_OPTIONS=false keeps the stored OPTION on dated cards only, isOption still needs a live hold`() {
        val exchangeRates = ExchangeRateCalculationService(mock(ExchangeRateRepository::class.java))
        val realMapper = YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer()))

        fun statusOf(call: List<Any?>): OfferStatus? =
            realMapper
                .toDto(
                    call[0] as YachtSearchSelectResult,
                    CurrencyEnum.EUR,
                    LanguageEnum.EN,
                    call[3] as Boolean,
                    keepStoredOption = call[12] as Boolean,
                ).offerStatus

        // default (true): today's demotion
        val today = search(request("S 7n")).call(OPTION_BOAT)
        assertEquals(OfferStatus.OPTION.value, (today[0] as YachtSearchSelectResult).offerStatus)
        assertEquals(false, today[3])
        assertEquals(false, today[12])
        assertEquals(OfferStatus.FREE, statusOf(today))

        settings.set(SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS, "false")
        val kept = search(request("S 7n")).call(OPTION_BOAT)
        assertEquals(false, kept[3], "no live hold -> no expiry stamp")
        assertEquals(true, kept[12])
        assertEquals(OfferStatus.OPTION, statusOf(kept))
        // undated: never an option badge
        val undated = search(request("undated")).call(OPTION_BOAT)
        assertEquals(false, undated[12])
        assertEquals(OfferStatus.FREE, statusOf(undated))
    }
}
