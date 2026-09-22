package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
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
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.function.Executable
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * 22.9.2026: `GET /public/yachts?…&sortBy=asc&size=100` over Croatian catamarans reported 897 rows on
 * 9 pages, but walking the pages yielded only 894 DISTINCT yachts — three came back on two neighbouring
 * pages and three others on none. Every `sortBy` branch ordered by its key alone (searched-week total,
 * deposit, length, partner boost) and hundreds of yachts tie on those keys. Postgres gives no stable
 * order among ties, and its bounded top-N heap sort even orders the SAME ties differently for each
 * LIMIT/OFFSET, so consecutive page boundaries cut through a tie at different places. Paging is stable
 * only under a total order — hence the trailing `id` tiebreak on every sort (the query groups by id).
 *
 * Real Hibernate + real Postgres (Testcontainers) on the real R__1_03 matview, the recipe of
 * [YachtSearchViewRefresherTest]: a criteria ORDER BY cannot be proven stable with mocks. Minimal
 * schema = the columns the view reads + the three tables the search query touches next to the view
 * (hard-block subquery, card amenities). The seed is an all-tie catalogue: every yacht carries the same
 * price, list price, deposit, length and agency, so every sort key ties on every row.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchPagingStabilityTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val YACHTS = 300
        private const val PAGE_SIZE = 30
        private val WEEK_FROM: LocalDate = LocalDate.of(2026, 10, 3)
        private val WEEK_TO: LocalDate = LocalDate.of(2026, 10, 10)

        /** Every `sortBy` the controller accepts, plus the empty and unknown values that fall back to Recommended. */
        private val SORT_VARIANTS =
            listOf("asc", "desc", "lowestPrepayment", "discount", "lengthAsc", "lengthDesc", "recommendedScore", "recommended", "", "bogus")

        /** Only the columns R__1_03_yacht_search_view.sql reads, plus the tables the search query joins beside the view. */
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
            """.trimIndent()

        /** One agency, one marina, [YACHTS] identical catamarans each with one identical FREE week. */
        private val ALL_TIE_SEED =
            """
            INSERT INTO location (id, display_name, country_code) VALUES (1, 'Marina Kastela | Split', 'HR');
            INSERT INTO agency (id, name, active, availability_blocked, recommended) VALUES (1, 'Tie agency', true, false, false);
            INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, build_year, max_persons, cabins, berths, length, wc)
              SELECT g, 'Tie yacht ' || g, 1, 'EXTERNAL', true, 'CATAMARAN', 2020, 8, 4, 8, 12.5, 2 FROM generate_series(1, $YACHTS) g;
            INSERT INTO yacht_charter_type (id, yacht_id, type) SELECT g, g, 'BAREBOAT' FROM generate_series(1, $YACHTS) g;
            INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price,
                               broker_commission, deposit, status)
              SELECT g, g, 1, 1, DATE '$WEEK_FROM', DATE '$WEEK_TO', 3500, 4200, 350, 1750, 'FREE' FROM generate_series(1, $YACHTS) g;
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
    private val yachtMapper: YachtMapper = mock(YachtMapper::class.java)
    private lateinit var service: YachtQueryingService

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(MINIMAL_SCHEMA)
        jdbcTemplate.execute(ALL_TIE_SEED)
        // Seed first, then build the matview: CREATE MATERIALIZED VIEW … AS SELECT populates it.
        applyRepeatableLikeFlyway(jdbcTemplate)

        entityManagerFactory = buildEntityManagerFactory()
        entityManager = entityManagerFactory.createEntityManager()
        service =
            YachtQueryingService(
                entityManager,
                mock(YachtRepository::class.java),
                mock(LocationRepository::class.java),
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
                mock(RegionRepository::class.java),
                mock(CountryRepository::class.java),
            )
    }

    @AfterAll
    fun tearDown() {
        entityManager.close()
        entityManagerFactory.close()
        dataSource.close()
    }

    /** The whole R__1_03 file on one connection in one transaction — exactly what Flyway does. */
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

    /** Real Hibernate over the production entity mappings, without a Spring context; no schema generation. */
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

    private fun searchParams(dated: Boolean): YachtSearchParamObject =
        YachtSearchParamObject(
            locationIds = null,
            charterTypes = null,
            vesselTypes = listOf(VesselType.CATAMARAN),
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
            startDate = WEEK_FROM.takeIf { dated },
            endDate = WEEK_TO.takeIf { dated },
            minWc = null,
            maxWc = null,
            minEnginePower = null,
            maxEnginePower = null,
            currency = CurrencyEnum.EUR,
            amenities = null,
            services = null,
            yachtIds = null,
            language = LanguageEnum.EN,
        )

    /**
     * Walks every page the way the admin Offers workspace and the web listing do, page 0 upwards until
     * `totalPages`. The yacht ids of a page are read off the mapper calls (one `toDto` per row, in page
     * order), so the check stays on the query and never on DTO shape.
     */
    private fun walk(
        sortBy: String,
        dated: Boolean,
    ): List<List<Long>> {
        val params = searchParams(dated)
        val pages = mutableListOf<List<Long>>()
        var page = 0
        do {
            clearInvocations(yachtMapper)
            val result = service.getYachts(params, sortBy, LanguageEnum.EN, page, PAGE_SIZE, isAdmin = false)
            assertEquals(YACHTS.toLong(), result.totalElements, "sortBy='$sortBy' dated=$dated: totalElements")
            pages += mockingDetails(yachtMapper).invocations.map { it.getArgument<YachtSearchSelectResult>(0).id }
            page++
        } while (page < result.totalPages)
        return pages
    }

    private fun pagesAreDisjointCompleteAndRepeatable(
        sortBy: String,
        dated: Boolean,
    ): Executable =
        Executable {
            val label = "sortBy='$sortBy' dated=$dated"
            val pages = walk(sortBy, dated)
            val seen = mutableSetOf<Long>()
            val duplicated = pages.flatten().filter { !seen.add(it) }.toSortedSet()
            val missing = ((1L..YACHTS).toSet() - seen).toSortedSet()
            assertEquals(emptySet<Long>(), duplicated, "$label: yachts returned on more than one page")
            assertEquals(emptySet<Long>(), missing, "$label: yachts returned on no page at all")
            assertEquals(pages, walk(sortBy, dated), "$label: two walks of the same search must page identically")
        }

    @Test
    fun `every sort variant pages an all-tie catalogue into disjoint, complete and repeatable pages`() {
        assertAll(
            SORT_VARIANTS.flatMap { sortBy ->
                listOf(
                    pagesAreDisjointCompleteAndRepeatable(sortBy, dated = true),
                    pagesAreDisjointCompleteAndRepeatable(sortBy, dated = false),
                )
            },
        )
    }
}
