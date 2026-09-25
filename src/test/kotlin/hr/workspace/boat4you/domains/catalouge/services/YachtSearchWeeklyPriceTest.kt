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
import org.springframework.jdbc.core.ConnectionCallback
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
import kotlin.test.assertNull

/**
 * 25.9.2026: the undated Greece landing listed "7 days 0 €" (Sun Odyssey 479 Sirius), "1 day
 * 211 €", "2 days 231 €", fourteen "3 days …" and "7 days 294 €" (a 46 ft yacht) side by side,
 * cheapest first. Without dates the listing priced a yacht as MIN(per-day) × MIN(days) over ALL
 * its offer rows — past weeks, sold weeks, 0 € sync noise and short stays included, the rate and
 * the day count possibly from different offers.
 *
 * `priceBasis=week` (weeklyPrice) prices an undated yacht by its cheapest bookable 7-night offer:
 * future (or custom), positive, not RESERVED/SERVICE, and not an outlier typo below 12 % of the
 * yacht's dearest week. No such week → NULL price (the web shows "price on request") and the
 * yacht sorts after every priced one. The default path only stops reading 0 € when a positive
 * row exists. Real Hibernate + Postgres on the real R__1_03 matview, recipe of
 * [YachtSearchPagingStabilityTest].
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchWeeklyPriceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val TODAY: LocalDate = LocalDate.now()

        /** A future Saturday-ish week start, n weeks from now. */
        private fun week(n: Long): LocalDate = TODAY.plusWeeks(n)

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

        /** One offer row; client/list are the offer TOTALS (the view divides them per day). */
        private data class SeedOffer(
            val yacht: Int,
            val from: LocalDate,
            val nights: Int,
            val client: Number,
            val list: Number,
            val status: String,
        )

        private val OFFERS: List<SeedOffer> =
            listOf(
                // 1 "Mixed": past cheap week, 0 € week, sold cheap week, a short stay, two real weeks.
                SeedOffer(1, TODAY.minusWeeks(20), 7, 700, 800, "FREE"),
                SeedOffer(1, week(2), 7, 0, 0, "FREE"),
                SeedOffer(1, week(3), 7, 1500, 1600, "RESERVED"),
                SeedOffer(1, week(4), 3, 300, 400, "FREE"),
                SeedOffer(1, week(5), 7, 2100, 2800, "FREE"),
                SeedOffer(1, week(6), 7, 3500, 3500, "OPTION"),
                // 2 "Short stays only".
                SeedOffer(2, week(2), 3, 450, 500, "FREE"),
                SeedOffer(2, week(3), 1, 150, 150, "FREE"),
                // 3 "Typo": one week at a tenth of the others.
                SeedOffer(3, week(2), 7, 294.5, 310, "FREE"),
                SeedOffer(3, week(3), 7, 2945, 3100, "FREE"),
                SeedOffer(3, week(4), 7, 4940, 5200, "FREE"),
                // 4 "Only 0 €".
                SeedOffer(4, week(2), 7, 0, 0, "FREE"),
                // 5 "Cheap real week", the cheapest priced yacht.
                SeedOffer(5, week(8), 7, 1400, 1400, "FREE"),
                SeedOffer(5, week(9), 7, 1900, 1900, "FREE"),
                // 7 "0 € beside a real week" — for the default (non-weekly) path.
                SeedOffer(7, week(2), 7, 0, 0, "FREE"),
                SeedOffer(7, week(3), 7, 700, 700, "FREE"),
            )

        private val SEED: String =
            buildString {
                appendLine("INSERT INTO location (id, display_name, country_code) VALUES (1, 'Marina Kastela | Split', 'HR');")
                appendLine("INSERT INTO agency (id, name, active, availability_blocked, recommended) VALUES (1, 'Agency', true, false, false);")
                listOf(1, 2, 3, 4, 5, 7).forEach { id ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, location_id) " +
                            "VALUES ($id, 'Yacht $id', 1, 'EXTERNAL', true, 'SAILING_YACHT', 1);",
                    )
                    appendLine("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES ($id, $id, 'BAREBOAT');")
                }
                // 6 "Custom": admin-managed, no offers, weekly low price 5,600 €.
                appendLine(
                    "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, location_id) " +
                        "VALUES (6, 'Yacht 6', 1, 'CUSTOM', true, 'SAILING_YACHT', 1);",
                )
                appendLine("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES (6, 6, 'BAREBOAT');")
                appendLine("INSERT INTO custom_yacht_details (yacht_id, low_price) VALUES (6, 5600);")
                OFFERS.forEachIndexed { i, (yacht, start, nights, client, list, status) ->
                    val end = start.plusDays(nights.toLong())
                    appendLine(
                        "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, " +
                            "ext_base_price, broker_commission, deposit, status) VALUES (${i + 1}, $yacht, 1, 1, " +
                            "DATE '$start', DATE '$end', $client, $list, 0, 1000, '$status');",
                    )
                }
            }
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
        jdbcTemplate.execute(SEED)
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
        weekly: Boolean,
        start: LocalDate? = null,
        end: LocalDate? = null,
    ): YachtSearchParamObject =
        YachtSearchParamObject(
            locationIds = null,
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
            startDate = start,
            endDate = end,
            minWc = null,
            maxWc = null,
            minEnginePower = null,
            maxEnginePower = null,
            currency = CurrencyEnum.EUR,
            amenities = null,
            services = null,
            yachtIds = null,
            weeklyPrice = weekly,
            language = LanguageEnum.EN,
        )

    /** Rows in page order, read off the mapper calls (one toDto per row). */
    private fun search(
        params: YachtSearchParamObject,
        sortBy: String = "",
    ): Pair<List<YachtSearchSelectResult>, Long> {
        clearInvocations(yachtMapper)
        val page = service.getYachts(params, sortBy, LanguageEnum.EN, 0, 50, isAdmin = false)
        val rows = mockingDetails(yachtMapper).invocations.map { it.getArgument<YachtSearchSelectResult>(0) }
        return rows to page.totalElements
    }

    /** The period total the card shows: per-day × days, to the cent. */
    private fun total(row: YachtSearchSelectResult): BigDecimal? {
        val perDay = row.clientPrice ?: return null
        val days = row.numberOfDays ?: return null
        return perDay.multiply(BigDecimal(days)).setScale(2, RoundingMode.HALF_UP)
    }

    @Test
    fun `weekly mode prices each yacht by its cheapest bookable week and lists the rest on request`() {
        val (rows, count) = search(params(weekly = true))
        val byId = rows.associateBy { it.id }

        assertEquals(7L, count, "the weekly price never filters yachts out")
        // Past week (700), 0 € week, the sold 1,500 week and the 3-night stay are ignored.
        assertEquals(BigDecimal("2100.00"), total(byId.getValue(1)))
        assertEquals(7, byId.getValue(1).numberOfDays)
        // List price of the same candidate rows: MIN(2,800 FREE, 3,500 OPTION) = 2,800.
        assertEquals(
            BigDecimal("2800.00"),
            byId.getValue(1).listPrice!!.multiply(BigDecimal(7)).setScale(2, RoundingMode.HALF_UP),
        )
        assertEquals(BigDecimal("1400.00"), total(byId.getValue(5)))
        assertEquals(BigDecimal("5600.00"), total(byId.getValue(6)), "custom yacht: its weekly low price")
        assertEquals(BigDecimal("700.00"), total(byId.getValue(7)))
        listOf(2L, 3L, 4L).forEach { id ->
            assertNull(byId.getValue(id).clientPrice, "yacht $id: no trustworthy week → no price")
            assertNull(byId.getValue(id).listPrice, "yacht $id: no list price either")
            assertNull(byId.getValue(id).numberOfDays, "yacht $id: no day count either")
        }
        // Default (recommended) order: priced yachts by weekly total, then the price-less ones by id.
        assertEquals(listOf(7L, 5L, 1L, 6L, 2L, 3L, 4L), rows.map { it.id })
    }

    @Test
    fun `weekly mode keeps price-less yachts last on a descending price sort`() {
        val (rows, _) = search(params(weekly = true), sortBy = "desc")

        assertEquals(listOf(6L, 1L, 5L, 7L, 2L, 3L, 4L), rows.map { it.id })
    }

    @Test
    fun `default undated pricing no longer reads a 0 € row when a positive one exists`() {
        val (rows, count) = search(params(weekly = false))
        val byId = rows.associateBy { it.id }

        assertEquals(7L, count)
        assertEquals(BigDecimal("700.00"), total(byId.getValue(7)), "yacht 7: its 700 € week, not the 0 € one")
        assertEquals(0, BigDecimal.ZERO.compareTo(byId.getValue(4).clientPrice), "yacht 4: only 0 € rows → still 0")
    }

    @Test
    fun `weekly flag is ignored on a dated search`() {
        val start = week(5)
        val end = start.plusDays(7)
        val (weeklyRows, _) = search(params(weekly = true, start = start, end = end))
        val (plainRows, _) = search(params(weekly = false, start = start, end = end))

        assertEquals(plainRows.map { it.id to total(it) }, weeklyRows.map { it.id to total(it) })
        assertEquals(BigDecimal("2100.00"), total(weeklyRows.first { it.id == 1L }))
    }
}
