package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityColumns
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.dto.BrokerNotesDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
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
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.core.io.ClassPathResource
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capacity contract v1 on the listing (2.2, 2.4, 9), real Hibernate + Postgres on the real R__1_03 matview and V9_72:
 * berths / WC in the positional multiselect, ONE per-page primary-key lookup carrying both the capacity columns and the
 * V9_71 modified time (the internal remark only for admins), the people filter on COALESCE(max_persons, berths) in the
 * listing and the facet counts alike, and the admin replacement search's native select + projection.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchCapacityTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val S: LocalDate = LocalDate.of(2030, 6, 15)
        private val E: LocalDate = S.plusDays(7)

        private val MINIMAL_SCHEMA =
            """
            CREATE TABLE location (id bigint PRIMARY KEY, display_name text, country_code varchar(2));
            CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false, recommended boolean);
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name text);
            CREATE TABLE model (id bigint PRIMARY KEY, name text, manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name text, agency_id bigint, entry_type text NOT NULL,
                                sys_active boolean NOT NULL DEFAULT true, location_id bigint, build_year integer,
                                model_id bigint, vessel_type text, mainsail_type text, max_persons smallint,
                                cabins smallint, berths smallint, length numeric, wc smallint, engine_power numeric,
                                main_image_id bigint, deposit numeric);
            CREATE TABLE yacht_charter_type (id bigint PRIMARY KEY, yacht_id bigint, type text);
            CREATE TABLE custom_yacht_details (yacht_id bigint PRIMARY KEY, low_price numeric);
            CREATE TABLE offer (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, location_from bigint NOT NULL,
                                location_to bigint, client_price numeric, ext_base_price numeric,
                                broker_commission numeric, date_from date NOT NULL, date_to date NOT NULL,
                                deposit numeric, status text NOT NULL);
            CREATE TABLE external_reservations (id bigint PRIMARY KEY, yacht_id bigint, date_from date NOT NULL,
                                date_to date NOT NULL, status text NOT NULL, option_expiration timestamp);
            CREATE TABLE equipment (id bigint PRIMARY KEY, name text, label_code text, category text,
                                match_keys text, filter_order smallint);
            CREATE TABLE yacht_equipment (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, equipment_id bigint,
                                name text, external_id bigint, highlight boolean NOT NULL DEFAULT false,
                                quantity numeric, comment text);
            CREATE TABLE yacht_content_modified (yacht_id bigint PRIMARY KEY, modified_at timestamptz NOT NULL);
            """.trimIndent()

        /** (id, max_persons, berths, cabins, wc) - 1 MMK with notes, 2 NauSys without max, 3 max < berths, 4 nothing. */
        private val BOATS = listOf(listOf(1, 10, 12, 4, 2), listOf(2, null, 8, 3, 2), listOf(3, 6, 10, 4, 2), listOf(4, null, null, 3, 1))

        private val SEED: String =
            buildString {
                appendLine("INSERT INTO location (id, display_name, country_code) VALUES (1, 'Marina Kastela | Split', 'HR');")
                appendLine("INSERT INTO agency (id, name, active, availability_blocked, recommended) VALUES (1, 'Agency', true, false, false);")
                BOATS.forEach { (id, max, berths, cabins, wc) ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, location_id, max_persons, " +
                            "berths, cabins, wc) VALUES ($id, 'Yacht $id', 1, 'EXTERNAL', true, 'SAILING_YACHT', 1, $max, $berths, $cabins, $wc);",
                    )
                    appendLine("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES ($id, $id, 'BAREBOAT');")
                    appendLine(
                        "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, " +
                            "ext_base_price, broker_commission, deposit, status) VALUES ($id, $id, 1, 1, DATE '$S', DATE '$E', " +
                            "${2000 + id!!}, ${2000 + id}, 0, 1000, 'FREE');",
                    )
                }
            }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
    private val yachtMapper: YachtMapper = mock(YachtMapper::class.java)
    private lateinit var service: YachtQueryingService
    private lateinit var distribution: YachtDistributionService
    private lateinit var yachts: YachtRepository

    /** The same search with the REAL YachtMapper (real capacity mapper + sanitizer) and the real yacht repository. */
    private lateinit var realService: YachtQueryingService

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute(MINIMAL_SCHEMA)
        jdbc.execute(SEED)
        ListingTwinTestSupport.createAndRefresh(jdbc)
        YachtCapacityTestSupport.addColumns(jdbc)
        OfferCharterTestSupport.addTables(jdbc)
        jdbc.update(
            "UPDATE yacht SET cabins_note = '4 +2', berths_note = '(8+2)', internal_remark = 'CREWED | private', crew_number = 1 WHERE id = 1",
        )
        jdbc.update("UPDATE yacht SET cabin_berths = 6, salon_berths = 2, crew_berths = 0, crew_wc = 0, recommended_persons = 7 WHERE id = 2")
        jdbc.update("INSERT INTO yacht_content_modified (yacht_id, modified_at) VALUES (1, '2026-10-06 08:15:42.734+00')")
        applyRepeatableLikeFlyway(jdbc)

        entityManagerFactory = buildEntityManagerFactory()
        entityManager = entityManagerFactory.createEntityManager()
        val repositories = JpaRepositoryFactory(entityManager)
        val locations = repositories.getRepository(LocationRepository::class.java)
        val regions = repositories.getRepository(RegionRepository::class.java)
        val countries = repositories.getRepository(CountryRepository::class.java)
        yachts = repositories.getRepository(YachtRepository::class.java)
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
                mock(ExchangeRateCalculationService::class.java),
                mock(YachtExtraRepository::class.java),
                mock(ExternalBaseRepository::class.java),
                regions,
                countries,
                PassThroughHeavyQueries,
            )
        distribution = YachtDistributionService(entityManager, locations, countries, regions, PassThroughHeavyQueries, NoOpCacheManager())
        val exchangeRates = mock(ExchangeRateCalculationService::class.java)
        realService =
            YachtQueryingService(
                entityManager,
                yachts,
                locations,
                mock(ExternalReservationRepository::class.java),
                YachtMapper(exchangeRates, YachtExtrasMapper(exchangeRates), YachtCapacityMapper(CapacityFixtures.sanitizer())),
                mock(OfferRepository::class.java),
                mock(CustomYachtViewRepository::class.java),
                mock(CustomYachtDetailRepository::class.java),
                mock(YachtTranslationRepository::class.java),
                mock(OfferMapper::class.java),
                mock(FileSystemService::class.java),
                exchangeRates,
                mock(YachtExtraRepository::class.java),
                mock(ExternalBaseRepository::class.java),
                regions,
                countries,
                PassThroughHeavyQueries,
            )
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @AfterAll
    fun tearDown() {
        entityManager.close()
        entityManagerFactory.close()
        dataSource.close()
    }

    private fun applyRepeatableLikeFlyway(jdbcTemplate: JdbcTemplate) {
        val sql = ClassPathResource("db/migration/R__1_03_yacht_search_view.sql").inputStream.bufferedReader().readText()
        jdbcTemplate.execute(
            ConnectionCallback<Unit> { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { it.execute(sql) }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            },
        )
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

    private fun params(
        minPersons: Short? = null,
        maxPersons: Short? = null,
    ) = YachtSearchParamObject(
        locationIds = null,
        charterTypes = null,
        vesselTypes = null,
        manufacturers = null,
        models = null,
        mainSailTypes = null,
        minBuildYear = null,
        maxBuildYear = null,
        minPersons = minPersons,
        maxPersons = maxPersons,
        minCabins = null,
        maxCabins = null,
        minBerths = null,
        maxBerths = null,
        minLength = null,
        maxLength = null,
        minPrice = null,
        maxPrice = null,
        startDate = S,
        endDate = E,
        minWc = null,
        maxWc = null,
        minEnginePower = null,
        maxEnginePower = null,
        currency = CurrencyEnum.EUR,
        amenities = null,
        services = null,
        yachtIds = null,
        weeklyPrice = false,
        language = LanguageEnum.EN,
    )

    private data class Card(
        val row: YachtSearchSelectResult,
        val updatedAt: Instant?,
        val capacity: CapacityColumns?,
    )

    private fun search(
        params: YachtSearchParamObject,
        isAdmin: Boolean = false,
    ): Map<Long, Card> {
        clearInvocations(yachtMapper)
        service.getYachts(params, "id", LanguageEnum.EN, 0, 50, isAdmin)
        return mockingDetails(yachtMapper).invocations.associate {
            val row = it.getArgument<YachtSearchSelectResult>(0)
            row.id to Card(row, it.getArgument(8), it.getArgument(9))
        }
    }

    @Test
    fun `cards get berths and WC from the matview and the capacity columns + modified time from one page lookup`() {
        val cards = search(params())
        assertEquals(setOf(1L, 2L, 3L, 4L), cards.keys)
        val one = cards.getValue(1)
        assertEquals(12.toShort(), one.row.berths)
        assertEquals(2.toShort(), one.row.wc)
        assertEquals(4.toShort(), one.row.cabins)
        assertEquals(Instant.parse("2026-10-06T08:15:42Z"), one.updatedAt)
        assertEquals(
            CapacityColumns(cabins = 4, berths = 12, wc = 2, maxPersons = 10, crewNumber = 1, cabinsNote = "4 +2", berthsNote = "(8+2)"),
            one.capacity,
            "public search: no internal remark",
        )
        val two = cards.getValue(2)
        assertNull(two.updatedAt)
        assertEquals(
            CapacityColumns(
                cabins = 3,
                berths = 8,
                wc = 2,
                crewBerths = 0,
                crewWc = 0,
                cabinBerths = 6,
                salonBerths = 2,
                recommendedPersons = 7,
            ),
            two.capacity,
        )
        assertNull(cards.getValue(4).row.berths)
    }

    @Test
    fun `admin search also reads the internal remark`() {
        assertEquals("CREWED | private", search(params(), isAdmin = true).getValue(1).capacity!!.internalRemark)
    }

    @Test
    fun `people filter - max people on board, else berths - in the listing and the facet counts alike`() {
        // 1: max 10 / 12 berths; 2: no max, 8 berths; 3: max 6 / 10 berths (the max wins); 4: neither
        assertEquals(setOf(1L, 2L), search(params(minPersons = 8)).keys)
        assertEquals(2L, service.getYachtSearchTotalCount(params(minPersons = 8)))
        assertEquals(setOf(3L), search(params(maxPersons = 7)).keys)
        assertEquals(setOf(2L, 3L), search(params(minPersons = 6, maxPersons = 8)).keys)
        assertEquals(2L, distribution.getDistribution(startDate = S, endDate = E, minPersons = 8).byVesselType.values.sum())
        assertEquals(1L, distribution.getDistribution(startDate = S, endDate = E, maxPersons = 7).byVesselType.values.sum())
    }

    @Test
    fun `admin replacement search selects the capacity columns into its projection`() {
        val rows =
            yachts
                .findForReplacementSearch(
                    locationIds = listOf(-1L),
                    locationIdsEmpty = true,
                    agencyIds = listOf(-1L),
                    agencyIdsEmpty = true,
                    vesselTypes = listOf(""),
                    vesselTypesEmpty = true,
                    startDate = S,
                    endDate = E,
                    pageSize = 50,
                    pageOffset = 0,
                ).associateBy { it.id }
        assertEquals(setOf(1L, 2L, 3L, 4L), rows.keys)
        val one = rows.getValue(1)
        assertEquals(12.toShort(), one.berths)
        assertEquals(2.toShort(), one.wc)
        assertEquals("4 +2", one.cabinsNote)
        assertEquals("(8+2)", one.berthsNote)
        assertEquals("CREWED | private", one.internalRemark)
        assertEquals(1.toShort(), one.crewNumber)
        val two = rows.getValue(2)
        assertEquals(6.toShort(), two.cabinBerths)
        assertEquals(2.toShort(), two.salonBerths)
        assertEquals(7.toShort(), two.recommendedPersons)
        assertNull(two.maxPersons)
    }

    private fun signIn(vararg authorities: String?) {
        SecurityContextHolder.getContext().authentication =
            when {
                authorities.isEmpty() -> null
                authorities.single() == null -> AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
                else -> UsernamePasswordAuthenticationToken("someone", null, authorities.map { SimpleGrantedAuthority(it) })
            }
    }

    private fun replacementRows() = realService.getYachtsForReplacement(params(), LanguageEnum.EN, 0, 50).content.associateBy { it.id }

    @Test
    fun `raw notes and the internal remark reach SYSTEM_ADMIN only - listing, replacement search and the JSON`() {
        // no authentication, the anonymous token, a signed-in customer: never brokerNotes, never the remark text -
        // even when the query did select the remark (isAdmin = true reaches the SQL, the mapper still gates)
        for (who in listOf(emptyArray(), arrayOf<String?>(null), arrayOf<String?>("USER"), arrayOf<String?>("MANAGER"))) {
            signIn(*who)
            for (adminQuery in listOf(false, true)) {
                val cards = realService.getYachts(params(), "id", LanguageEnum.EN, 0, 50, adminQuery).content
                assertEquals(4, cards.size)
                assertTrue(cards.all { it.brokerNotes == null }, "listing brokerNotes for ${who.toList()}")
                val json = CapacityFixtures.mapper.writeValueAsString(cards)
                assertFalse(json.contains("CREWED | private"), "remark in the listing JSON for ${who.toList()}")
                assertTrue(json.contains("\"note\":\"4 +2\""), "the short public note stays")
            }
            val replacement = replacementRows()
            assertTrue(replacement.values.all { it.brokerNotes == null }, "replacement brokerNotes for ${who.toList()}")
            assertFalse(CapacityFixtures.mapper.writeValueAsString(replacement.values).contains("CREWED | private"))
        }

        signIn("SYSTEM_ADMIN")
        val admin = realService.getYachts(params(), "id", LanguageEnum.EN, 0, 50, true).content.associateBy { it.id }
        assertEquals(BrokerNotesDto(cabinsNote = "4 +2", berthsNote = "(8+2)", headsNote = null, remark = "CREWED | private"), admin.getValue(1).brokerNotes)
        assertEquals(BrokerNotesDto(cabinsNote = null, berthsNote = null, headsNote = null, remark = null), admin.getValue(2).brokerNotes)
        assertEquals("CREWED | private", replacementRows().getValue(1).brokerNotes?.remark)
    }
}
