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
import kotlin.test.assertTrue

/**
 * 26.9.2026 audit B15: a dated search whose length differs from the boat's offers priced one offer and linked another.
 * "Croatia, 12-15 June 2027" (3 nights): 16 of 18 cards said "Price for 7 days" and 14 linked startDate = endDate =
 * 12 June (the end of the week BEFORE), one linked an end date before its start. Each card now shows ONE offer - the
 * exact period, else the searched length nearest the searched start, else an offer covering the searched nights, else
 * the nearest - with that offer's own price, day count and dates; searched-length cards rank before longer ones, cards
 * without a price last. Also: the undated weekly card carries its priced week's dates, and no path shows 0 EUR or a
 * 10 EUR placeholder week. Real Hibernate + Postgres on the real R__1_03 matview (recipe of YachtSearchWeeklyPriceTest).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchDatedOfferTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val TODAY: LocalDate = LocalDate.now()

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

        /** One offer row; client/list are the offer TOTALS (the view divides them per day). */
        private data class SeedOffer(
            val yacht: Int,
            val from: LocalDate,
            val nights: Int,
            val client: Number,
            val status: String = "FREE",
        )

        private val S: LocalDate = LocalDate.of(2030, 6, 15)
        private val E: LocalDate = S.plusDays(3)

        /** A Saturday far from the other fixtures: yacht 9's placeholder weeks. */
        private val P: LocalDate = LocalDate.of(2030, 8, 3)

        private val OFFERS: List<SeedOffer> =
            listOf(
                // 1: only weeks - the week BEFORE (ends on the searched start) and the week that covers the search
                SeedOffer(1, S.minusDays(7), 7, 1000),
                SeedOffer(1, S, 7, 2000),
                // 2: a 3-night offer one day later, and the covering week
                SeedOffer(2, S.plusDays(1), 3, 900),
                SeedOffer(2, S, 7, 2100),
                // 3: the exact 3 nights, and the covering week
                SeedOffer(3, S, 3, 1200),
                SeedOffer(3, S, 7, 1800),
                // 4: 0 EUR rows only
                SeedOffer(4, S, 7, 0),
                SeedOffer(4, S, 3, 0),
                // 5: a Thursday week covering the search (2 days before) and the next one (5 days after)
                SeedOffer(5, S.minusDays(2), 7, 1500),
                SeedOffer(5, S.plusDays(5), 7, 1400),
                // 6: two weeks that tile a 14-night search exactly
                SeedOffer(6, LocalDate.of(2030, 7, 6), 7, 1000),
                SeedOffer(6, LocalDate.of(2030, 7, 13), 7, 1100),
                // 7: undated weekly - a past week, two future weeks
                SeedOffer(7, TODAY.minusWeeks(2), 7, 500),
                SeedOffer(7, TODAY.plusWeeks(2), 7, 2100),
                SeedOffer(7, TODAY.plusWeeks(3), 7, 1500),
                // 8: undated weekly - a grid of 10 EUR placeholder weeks
                SeedOffer(8, TODAY.plusWeeks(2), 7, 10),
                SeedOffer(8, TODAY.plusWeeks(3), 7, 10),
                // 9: dated - a grid of 10 EUR placeholder weeks (Valencia, 1.49 EUR a day), and one 3-night row at
                // 120 EUR (40 EUR a night, below the 300 EUR-a-week floor)
                SeedOffer(9, P, 7, 10),
                SeedOffer(9, P.plusDays(7), 7, 10),
                SeedOffer(9, P.plusDays(14), 7, 10),
                SeedOffer(9, P.plusDays(7), 3, 120),
            )

        private val SEED: String =
            buildString {
                appendLine("INSERT INTO location (id, display_name, country_code) VALUES (1, 'Marina Kastela | Split', 'HR');")
                appendLine("INSERT INTO agency (id, name, active, availability_blocked, recommended) VALUES (1, 'Agency', true, false, false);")
                (1..9).forEach { id ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, location_id) " +
                            "VALUES ($id, 'Yacht $id', 1, 'EXTERNAL', true, 'SAILING_YACHT', 1);",
                    )
                    appendLine("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES ($id, $id, 'BAREBOAT');")
                }
                OFFERS.forEachIndexed { i, (yacht, start, nights, client, status) ->
                    val end = start.plusDays(nights.toLong())
                    appendLine(
                        "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, " +
                            "ext_base_price, broker_commission, deposit, status) VALUES (${i + 1}, $yacht, 1, 1, " +
                            "DATE '$start', DATE '$end', $client, $client, 0, 1000, '$status');",
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
        ListingTwinTestSupport.createAndRefresh(jdbcTemplate)
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
                PassThroughHeavyQueries,
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

    private fun byId(rows: List<YachtSearchSelectResult>) = rows.associateBy { it.id }

    @Test
    fun `a 3-night search - one offer per card, its own price, day count and dates`() {
        val (rows, count) = search(params(weekly = true, start = S, end = E))
        val byId = byId(rows)
        assertEquals(5L, count)

        fun check(
            id: Long,
            total: String?,
            days: Int?,
            from: LocalDate?,
            to: LocalDate?,
        ) {
            val row = byId.getValue(id)
            assertEquals(total?.let { BigDecimal(it) }, total(row), "yacht $id total")
            assertEquals(days, row.numberOfDays, "yacht $id days")
            assertEquals(from, row.offerDateFrom, "yacht $id from")
            assertEquals(to, row.offerDateTo, "yacht $id to")
            if (from != null && to != null) assertTrue(to.isAfter(from), "yacht $id: a card never links end <= start")
        }
        // the covering week, not "7 days 1,000" of the week before with an end date = the searched start
        check(1, "2000.00", 7, S, S.plusDays(7))
        // the searched length, one day later - not the week
        check(2, "900.00", 3, S.plusDays(1), S.plusDays(4))
        check(3, "1200.00", 3, S, E)
        check(4, null, null, null, null)
        // the week covering the search (starts 2 days before), not the cheaper week starting 5 days after
        check(5, "1500.00", 7, S.minusDays(2), S.plusDays(5))

        // searched-length cards first (cheapest first), then the longer offers, then no price
        assertEquals(listOf(2L, 3L, 5L, 1L, 4L), rows.map { it.id })
        val (desc, _) = search(params(weekly = false, start = S, end = E), sortBy = "desc")
        assertEquals(listOf(3L, 2L, 1L, 5L, 4L), desc.map { it.id })
    }

    @Test
    fun `a 14-night search tiled by two weeks shows the period total and links the searched dates`() {
        val start = LocalDate.of(2030, 7, 6)
        val end = start.plusDays(14)
        val (rows, _) = search(params(weekly = false, start = start, end = end))
        val row = rows.single { it.id == 6L }
        assertEquals(BigDecimal("2100.00"), total(row))
        assertEquals(14, row.numberOfDays)
        assertEquals(start, row.offerDateFrom)
        assertEquals(end, row.offerDateTo)
    }

    @Test
    fun `undated weekly card - the priced week's own dates, and no 10 EUR placeholder price`() {
        val (rows, _) = search(params(weekly = true))
        val byId = byId(rows)
        val seven = byId.getValue(7)
        assertEquals(BigDecimal("1500.00"), total(seven))
        assertEquals(TODAY.plusWeeks(3), seven.offerDateFrom)
        assertEquals(TODAY.plusWeeks(3).plusDays(7), seven.offerDateTo)
        val eight = byId.getValue(8)
        assertNull(eight.clientPrice, "a 10 EUR week is a placeholder, never a weekly price")
        assertNull(eight.offerDateFrom)
    }

    @Test
    fun `the default undated path never returns a price of 0 or less`() {
        val (rows, _) = search(params(weekly = false))
        rows.forEach { row ->
            val price = row.clientPrice
            assertTrue(price == null || price > BigDecimal.ZERO, "yacht ${row.id}: $price")
        }
        assertNull(byId(rows).getValue(4).clientPrice, "yacht 4: only 0 EUR rows -> no price")
        assertNull(byId(rows).getValue(8).clientPrice, "yacht 8: only 10 EUR weeks -> no price, never 1.43 EUR a day")
        assertNull(byId(rows).getValue(9).clientPrice, "yacht 9: only placeholder rows -> no price")
    }

    @Test
    fun `dated 7- and 14-night searches over 10 EUR placeholder weeks - price on request, never 10 or 21 EUR`() {
        listOf(7L, 14L, 3L).forEach { nights ->
            val start = if (nights == 3L) P.plusDays(7) else P
            val (rows, _) = search(params(weekly = false, start = start, end = start.plusDays(nights)))
            val nine = rows.single { it.id == 9L }
            assertNull(nine.clientPrice, "$nights nights: a placeholder is never the card's price")
            assertNull(nine.numberOfDays, "$nights nights")
            assertNull(nine.offerDateFrom, "$nights nights: no offer dates, the card links the searched pair")
            assertNull(nine.offerDateTo, "$nights nights")
            // ranked after every priced card
            assertEquals(9L, search(params(weekly = false, start = start, end = start.plusDays(nights)), sortBy = "desc").first.last().id)
        }
    }
}
