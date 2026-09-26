package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.services.FileSystemService
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
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.core.io.ClassPathResource
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * 26.9.2026 audit B14 / B12: a destination lists the boats BASED there, and only bookable ones.
 *  - pickup only: the Dubrovnik catamaran landing listed Kaštela boats with a one-way week into Dubrovnik;
 *  - one physical marina, all its rows: "Marina Kaštela" / "Marina Kastela" (same spelling) and the curated
 *    "D-Marin Marina Lefkas" / "Lefkas, D-Marin" (location_same_place) - but never a same-named marina 170 km away;
 *  - undated = bookable: a boat whose offers all lie in the past is neither listed nor counted.
 * The search and the sidebar facets must agree on all of it. Real Hibernate + Postgres, real R__1_03 matview, real
 * LocationRepository / RegionRepository / CountryRepository native and JPQL queries.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchScopeTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val TODAY: LocalDate = LocalDate.now()

        private val SCHEMA =
            """
            CREATE TABLE country (id int PRIMARY KEY, name varchar(100), code2 varchar(2) NOT NULL, code3 varchar(3) NOT NULL,
                                  continent varchar(15) NOT NULL);
            CREATE TABLE region (id serial PRIMARY KEY, name varchar(100), country_id int, country_code varchar(2));
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255) NOT NULL, country_code varchar(2) NOT NULL,
                                   lat numeric, lon numeric, country_id int NOT NULL, city varchar(100),
                                   inland boolean NOT NULL DEFAULT false, display_name text);
            CREATE TABLE location_region (region_id int NOT NULL, location_id bigint NOT NULL);
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
            """.trimIndent()

        private val SEED =
            buildString {
                appendLine("INSERT INTO country VALUES (54, 'Croatia', 'HR', 'HRV', 'Europe'), (86, 'Greece', 'GR', 'GRC', 'Europe');")
                appendLine(
                    """
                    INSERT INTO location (id, name, country_code, lat, lon, country_id, city) VALUES
                        (1, 'Marina Kaštela', 'HR', 43.5516, 16.3627, 54, 'Kaštel Gomilica'),
                        (2, 'Marina Kastela', 'HR', NULL, NULL, 54, NULL),
                        (3, 'ACI Marina Dubrovnik', 'HR', 42.6710, 18.1240, 54, 'Dubrovnik'),
                        (4, 'Marina Frapa', 'HR', 43.5310, 15.9640, 54, 'Rogoznica'),
                        (5, 'Marina Frapa Dubrovnik', 'HR', 42.6700, 18.0800, 54, 'Dubrovnik'),
                        (7, 'Port Veli', 'HR', 45.0000, 14.0000, 54, 'Veli Lošinj'),
                        (10, 'Port Veli', 'HR', 43.0000, 17.0000, 54, 'Veli Rat'),
                        (11, 'Port Veli', 'HR', NULL, NULL, 54, NULL),
                        (8, 'D-Marin Marina Lefkas', 'GR', 38.8300, 20.7100, 86, 'Lefkada'),
                        (9, 'Lefkas, D-Marin', 'GR', NULL, NULL, 86, NULL);
                    UPDATE location SET display_name = name || COALESCE(' | ' || city, '');
                    INSERT INTO region (id, name, country_id, country_code) VALUES (5, 'Split', 54, 'HR'), (6, 'Dubrovnik region', 54, 'HR');
                    INSERT INTO location_region VALUES (5, 1), (5, 2), (5, 4), (5, 7), (6, 3), (6, 5);
                    INSERT INTO agency (id, name) VALUES (1, 'Agency'), (2, 'Broker');
                    """.trimIndent(),
                )
                // yacht -> home base
                val boats =
                    mapOf(
                        101 to 1, 102 to 1, 103 to 3, 104 to 2, 105 to 3, 106 to 4, 107 to 5, 108 to 7, 109 to 8, 110 to 9,
                        111 to 10, 112 to 11,
                    )
                boats.forEach { (id, home) ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, vessel_type, location_id) VALUES " +
                            "($id, 'Yacht $id', 1, 'EXTERNAL', 'CATAMARAN', $home);",
                    )
                    appendLine("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES ($id, $id, 'BAREBOAT');")
                }
                // B17: 113 "Pampero" and 114 "PAMPERO" (a broker's listing of the same boat, a year apart) at Frapa
                // Dubrovnik; 115 / 116 are two fleet boats one agency names alike - not duplicates
                appendLine(
                    """
                    INSERT INTO yacht (id, name, agency_id, entry_type, vessel_type, location_id, build_year, length) VALUES
                        (113, 'Pampero', 1, 'EXTERNAL', 'CATAMARAN', 5, 2018, 11.55),
                        (114, 'PAMPERO', 2, 'EXTERNAL', 'CATAMARAN', 5, 2019, 11.60),
                        (115, 'Fleet Cat Exclusive', 1, 'EXTERNAL', 'CATAMARAN', 5, 2020, 12.00),
                        (116, 'Fleet Cat Exclusive', 1, 'EXTERNAL', 'CATAMARAN', 5, 2020, 12.00);
                    INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES (113, 113, 'BAREBOAT'), (114, 114, 'BAREBOAT'),
                        (115, 115, 'BAREBOAT'), (116, 116, 'BAREBOAT');
                    """.trimIndent(),
                )
                var offerId = 0
                fun offer(
                    yacht: Int,
                    from: Int,
                    to: Int,
                    start: LocalDate,
                    price: Int = 2100,
                ) = appendLine(
                    "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price, " +
                        "broker_commission, deposit, status) VALUES (${++offerId}, $yacht, $from, $to, DATE '$start', " +
                        "DATE '${start.plusDays(7)}', $price, $price, 0, 1000, 'FREE');",
                )
                val w = TODAY.plusWeeks(3)
                boats.forEach { (id, home) -> if (id != 105) offer(id, home, home, w) }
                // 102: also a one-way week from Kaštela INTO Dubrovnik
                offer(102, 1, 3, w.plusWeeks(1), 2500)
                // 105: based in Dubrovnik, but every offer is in the past (still in the matview for 30 days)
                offer(105, 3, 3, TODAY.minusDays(17))
                // the twins and the fleet boats: 113 has the fuller calendar, so it is the copy shown
                listOf(113, 114, 115, 116).forEach { offer(it, 5, 5, w) }
                offer(113, 5, 5, w.plusWeeks(1))
            }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
    private val yachtMapper: YachtMapper = mock(YachtMapper::class.java)
    private lateinit var service: YachtQueryingService
    private lateinit var distribution: YachtDistributionService

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
        jdbc.execute(SCHEMA)
        val sameplace = ClassPathResource("db/migration/V9_67__location_same_place.sql").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $sameplace; COMMIT;")
        jdbc.execute(SEED)
        jdbc.update("INSERT INTO location_same_place (location_id, same_as_location_id) VALUES (8, 9)")
        ListingTwinTestSupport.createAndRefresh(jdbc)
        applyRepeatable(jdbc, "R__1_03_yacht_search_view.sql")

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
                mock(ExchangeRateCalculationService::class.java),
                mock(YachtExtraRepository::class.java),
                mock(ExternalBaseRepository::class.java),
                regions,
                countries,
            )
        distribution = YachtDistributionService(entityManager, locations, countries, regions)
    }

    @AfterAll
    fun tearDown() {
        entityManager.close()
        entityManagerFactory.close()
        dataSource.close()
    }

    private fun applyRepeatable(
        jdbc: JdbcTemplate,
        file: String,
    ) {
        val sql = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
        jdbc.execute(
            ConnectionCallback<Unit> { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { it.execute(sql) }
                    conn.commit()
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

    private fun params(did: List<String>): YachtSearchParamObject =
        YachtSearchParamObject(
            locationIds = did,
            charterTypes = null,
            vesselTypes = null,
            manufacturers = null,
            models = null,
            mainSailTypes = null,
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
            currency = CurrencyEnum.EUR,
            amenities = null,
            services = null,
            yachtIds = null,
            weeklyPrice = true,
            language = LanguageEnum.EN,
        )

    /** Listed ids, the H2 total and the facet total (sum of the type chips) of one undated landing. */
    private fun landing(vararg did: String): Triple<List<Long>, Long, Long> {
        clearInvocations(yachtMapper)
        val page = service.getYachts(params(did.toList()), "", LanguageEnum.EN, 0, 50, isAdmin = false)
        val ids = mockingDetails(yachtMapper).invocations.map { it.getArgument<YachtSearchSelectResult>(0).id }
        val chips = distribution.getDistribution(locationIds = did.toList()).byVesselType.values.sum()
        return Triple(ids, page.totalElements, chips)
    }

    @Test
    fun `a marina lists the boats that start there, not a one-way week into it, nor a boat with only past offers`() {
        val (ids, total, chips) = landing("l-3")
        ids shouldContainExactlyInAnyOrder listOf(103L)
        total shouldBe 1L
        chips shouldBe 1L
    }

    @Test
    fun `a region lists its based boats only - Frapa Rogoznica is not in the Dubrovnik region`() {
        val (ids, total, chips) = landing("r-6")
        ids shouldContainExactlyInAnyOrder listOf(103L, 107L, 113L, 115L, 116L)
        total shouldBe 5L
        chips shouldBe 5L
    }

    @Test
    fun `B17 - one card per physical boat undated, every copy on a dated search`() {
        landing("l-5").first.contains(114L) shouldBe false
        clearInvocations(yachtMapper)
        val start = TODAY.plusWeeks(3)
        service.getYachts(params(listOf("l-5")).copy(startDate = start, endDate = start.plusDays(7)), "", LanguageEnum.EN, 0, 50, false)
        mockingDetails(yachtMapper).invocations.map { it.getArgument<YachtSearchSelectResult>(0).id } shouldContainExactlyInAnyOrder
            listOf(107L, 113L, 114L, 115L, 116L)
    }

    @Test
    fun `one marina, all its rows - never the same name 170 km away`() {
        landing("l-1").first shouldContainExactlyInAnyOrder listOf(101L, 102L, 104L)
        landing("l-2").first shouldContainExactlyInAnyOrder listOf(101L, 102L, 104L)
        // one name, two places 280 km apart: each keeps its own boats, the row without data cannot be placed
        landing("l-7").first shouldContainExactlyInAnyOrder listOf(108L)
        landing("l-10").first shouldContainExactlyInAnyOrder listOf(111L)
        landing("l-11").first shouldContainExactlyInAnyOrder listOf(112L)
        // Frapa Dubrovnik is not Frapa (Rogoznica); 114 is a second listing of 113 (another channel), 115 / 116 are
        // two fleet boats of one agency
        landing("l-5").first shouldContainExactlyInAnyOrder listOf(107L, 113L, 115L, 116L)
        // the curated D-Marin Lefkas pair
        landing("l-8").first shouldContainExactlyInAnyOrder listOf(109L, 110L)
        landing("l-9").first shouldContainExactlyInAnyOrder listOf(109L, 110L)
    }

    @Test
    fun `a country counts bookable boats - the H2 and the chips agree`() {
        val (ids, total, chips) = landing("c-54")
        ids shouldContainExactlyInAnyOrder listOf(101L, 102L, 103L, 104L, 106L, 107L, 108L, 111L, 112L, 113L, 115L, 116L)
        total shouldBe 12L
        chips shouldBe 12L
    }
}
