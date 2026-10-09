package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterBasis
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterDto
import hr.workspace.boat4you.domains.catalouge.dto.OfferCharterKind
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchResponseDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.ALL_INCLUSIVE
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.BAREBOAT
import hr.workspace.boat4you.domains.catalouge.enums.CharterType.CREWED
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
import org.mockito.Mockito.mock
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.core.io.ClassPathResource
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "GULET NIKAD NIJE BAREBOAT" (Mario 9.10.2026) and the admin Offers pill, on real Hibernate + Postgres and the real
 * R__1_03 matview: the listing's charter type, the charterType filter, its total and the facet counts treat every gulet
 * (by vessel type or model name) as crewed and never bareboat, every other boat keeps its partner type; an ADMIN listing
 * carries Bareboat / Skippered / Crewed for the offer each card shows, a public one never does (not even as a key).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchGuletCharterTest {
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

        /** A two-week search far from S..E: boats 17 / 18 only have two weekly offers there (a tiled card). */
        private val S2: LocalDate = S.plusDays(70)
        private val E2: LocalDate = S2.plusDays(14)

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
            CREATE TABLE yacht_charter_type (id bigserial PRIMARY KEY, yacht_id bigint, type text);
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

        private data class Boat(
            val id: Long,
            val vesselType: String,
            val modelId: Long,
            val types: List<String>,
            /** offer product (STRING enum as stored); the offer is S..E, one per boat unless [extraOffers] */
            val product: String,
            val obligatory: List<String> = emptyList(),
            val optional: List<String> = emptyList(),
            val status: String = "FREE",
        )

        /** model 1 Oceanis 46.1, 2 "Gulet" (Gallant 11771), 3 Princess 62, 4 Lagoon 42 */
        private val BOATS =
            listOf(
                // a plain bareboat with a deposit row that only mentions skippers, and an OPTIONAL skipper
                Boat(1, "SAILING_YACHT", 1, listOf("BAREBOAT"), "BAREBOAT", listOf("Security Deposit* Valid ONLY for insured skippers"), listOf("Skipper")),
                // Sylvia R 18886: a gulet the partner tags BAREBOAT only
                Boat(2, "GULET", 1, listOf("BAREBOAT"), "BAREBOAT"),
                // Gallant 11771: MOTOR_YACHT of model "Gulet"
                Boat(3, "MOTOR_YACHT", 2, listOf("CREWED"), "CREWED"),
                // a gulet tagged bareboat + all-inclusive
                Boat(4, "GULET", 1, listOf("BAREBOAT", "ALL_INCLUSIVE"), "BAREBOAT"),
                // a crewed motor yacht (MMK product CREWED)
                Boat(5, "MOTOR_YACHT", 3, listOf("CREWED"), "CREWED"),
                // bareboat product, obligatory skipper on the offer
                Boat(6, "SAILING_YACHT", 1, listOf("BAREBOAT"), "BAREBOAT", listOf("Transit log", "Skipper + food")),
                // NauSys (no product), obligatory "One man crew" on the BOAT valid on the charter's first day
                Boat(7, "CATAMARAN", 4, listOf("BAREBOAT"), "UNKNOWN"),
                // NauSys crewed boat
                Boat(8, "CATAMARAN", 4, listOf("CREWED"), "UNKNOWN"),
                // MMK boat sold both ways; the skipper's insurance is not a skipper
                Boat(9, "SAILING_YACHT", 1, listOf("BAREBOAT", "CREWED"), "BAREBOAT", listOf("Skipper's liability insurance")),
                // a bareboat motor yacht whose crew is obligatory
                Boat(10, "MOTOR_YACHT", 3, listOf("BAREBOAT"), "BAREBOAT", listOf("3 crew: 1 captain, 1 cook and 1 deckhand")),
                // MMK boat sold both ways: its cheaper BAREBOAT offer is one-way (made one-way below)
                Boat(11, "SAILING_YACHT", 1, listOf("BAREBOAT", "CREWED"), "BAREBOAT"),
                // NauSys: the boat's obligatory skipper is limited to two other bases (prod 2583: Palma boat, "Skipper" valid
                // only for Nassau / Road Town) - the price calculation never charges it
                Boat(12, "SAILING_YACHT", 1, listOf("BAREBOAT"), "UNKNOWN"),
                // NauSys: the same row limited to bases that include the boat's home base - charged
                Boat(13, "SAILING_YACHT", 1, listOf("BAREBOAT"), "UNKNOWN"),
                // a live option holds this BAREBOAT offer; the boat page takes the week's FREE CREWED offer (added below)
                Boat(14, "SAILING_YACHT", 1, listOf("BAREBOAT", "CREWED"), "BAREBOAT", status = "OPTION"),
                // an obligatory skipper and an obligatory hostess: a crew
                Boat(15, "CATAMARAN", 4, listOf("BAREBOAT"), "BAREBOAT", listOf("Skipper", "Hostess")),
                // an obligatory hostess alone: still bareboat (the client skippers), named
                Boat(16, "CATAMARAN", 4, listOf("BAREBOAT"), "BAREBOAT", listOf("Hostess")),
            )

        /** Two-week boats: id to (first week's obligatory charges, second week's), both BAREBOAT products, S2..E2. */
        private val TWO_WEEK_BOATS = mapOf(17L to (emptyList<String>() to emptyList()), 18L to (emptyList<String>() to listOf("Skipper")))

        private val SEED: String =
            buildString {
                appendLine("INSERT INTO location (id, display_name, country_code) VALUES (1, 'Marina Kastela | Split', 'HR'), (2, 'ACI Marina Dubrovnik | Dubrovnik', 'HR');")
                appendLine("INSERT INTO agency (id, name, active, availability_blocked, recommended) VALUES (1, 'Agency', true, false, false);")
                appendLine("INSERT INTO manufacturer (id, name) VALUES (1, 'Beneteau'), (2, 'Custom'), (3, 'Princess'), (4, 'Lagoon');")
                appendLine("INSERT INTO model (id, name, manufacturer_id) VALUES (1, 'Oceanis 46.1', 1), (2, 'Gulet', 2), (3, 'Princess 62', 3), (4, 'Lagoon 42', 4);")
                BOATS.forEach { b ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, model_id, location_id, cabins) " +
                            "VALUES (${b.id}, 'Yacht ${b.id}', 1, 'EXTERNAL', true, '${b.vesselType}', ${b.modelId}, 1, 4);",
                    )
                    b.types.forEach { appendLine("INSERT INTO yacht_charter_type (yacht_id, type) VALUES (${b.id}, '$it');") }
                    appendLine(
                        "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, " +
                            "ext_base_price, broker_commission, deposit, status, product) VALUES (${b.id}, ${b.id}, 1, 1, DATE '$S', " +
                            "DATE '$E', ${3000 + b.id}, ${3000 + b.id}, 0, 1000, '${b.status}', '${b.product}');",
                    )
                    b.obligatory.forEach { appendLine("INSERT INTO offer_extras (offer_id, name, obligatory) VALUES (${b.id}, '${it.replace("'", "''")}', true);") }
                    b.optional.forEach { appendLine("INSERT INTO offer_extras (offer_id, name, obligatory) VALUES (${b.id}, '$it', false);") }
                }
                // boat 7: the obligatory skipper on the boat, valid this season; last season's row does not count
                appendLine(
                    "INSERT INTO yacht_extras (yacht_id, name, obligatory, valid_from, valid_to) " +
                        "VALUES (7, 'One man crew (Caribbean)', true, DATE '2030-01-01', DATE '2030-12-31');",
                )
                appendLine("INSERT INTO yacht_extras (yacht_id, name, obligatory, valid_from, valid_to) VALUES (8, 'Skipper', true, DATE '2029-01-01', DATE '2029-12-31');")
                appendLine("INSERT INTO yacht_extras (yacht_id, name, obligatory, valid_from, valid_to) VALUES (1, 'Skipper', true, DATE '2029-01-01', DATE '2029-12-31');")
                // boat 9: a second, CREWED round trip from the home base the same week - the boat page (and "Add to offer")
                // takes the first one, the BAREBOAT offer 9
                appendLine(
                    "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price, " +
                        "broker_commission, deposit, status, product) VALUES (90, 9, 1, 1, DATE '$S', DATE '$E', 9000, 9000, 0, 1000, 'FREE', 'CREWED');",
                )
                // boat 11: the card prices the cheaper one-way BAREBOAT offer, but the boat page - and "Add to offer" - takes
                // the round trip, a CREWED offer: the pill says what the broker adds
                appendLine("UPDATE offer SET location_to = 2 WHERE id = 11;")
                appendLine(
                    "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price, " +
                        "broker_commission, deposit, status, product) VALUES (110, 11, 1, 1, DATE '$S', DATE '$E', 9000, 9000, 0, 1000, 'FREE', 'CREWED');",
                )
                // the home base (agency 1, location 1) is NauSys base 500; boat 12's skipper is for bases 900 / 901 only
                appendLine("INSERT INTO external_bases (external_id, agency_id, location_id) VALUES (500, 1, 1), (900, 1, 2);")
                appendLine(
                    "INSERT INTO yacht_extras (yacht_id, name, obligatory, valid_from, valid_to, valid_for_bases) VALUES " +
                        "(12, 'Skipper', true, DATE '2030-01-01', DATE '2030-12-31', '{900,901}'), " +
                        "(13, 'Skipper', true, DATE '2030-01-01', DATE '2030-12-31', '{901,500}');",
                )
                // boat 14: a live option (no expiry) holds offer 14; the same week's round trip 140 is FREE and CREWED
                appendLine("INSERT INTO external_reservations (id, yacht_id, date_from, date_to, status) VALUES (1, 14, DATE '$S', DATE '$E', 'OPTION');")
                appendLine(
                    "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price, " +
                        "broker_commission, deposit, status, product) VALUES (140, 14, 1, 1, DATE '$S', DATE '$E', 9000, 9000, 0, 1000, 'FREE', 'CREWED');",
                )
                TWO_WEEK_BOATS.forEach { (id, weeks) ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, model_id, location_id, cabins) " +
                            "VALUES ($id, 'Yacht $id', 1, 'EXTERNAL', true, 'SAILING_YACHT', 1, 1, 4);",
                    )
                    appendLine("INSERT INTO yacht_charter_type (yacht_id, type) VALUES ($id, 'BAREBOAT');")
                    listOf(weeks.first, weeks.second).forEachIndexed { week, charges ->
                        val offerId = id * 100 + week
                        val from = S2.plusDays(7L * week)
                        appendLine(
                            "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, ext_base_price, " +
                                "broker_commission, deposit, status, product) VALUES ($offerId, $id, 1, 1, DATE '$from', DATE '${from.plusDays(7)}', " +
                                "3000, 3000, 0, 1000, 'FREE', 'BAREBOAT');",
                        )
                        charges.forEach { appendLine("INSERT INTO offer_extras (offer_id, name, obligatory) VALUES ($offerId, '$it', true);") }
                    }
                }
            }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
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
        jdbc.execute(MINIMAL_SCHEMA)
        OfferCharterTestSupport.addTables(jdbc)
        jdbc.execute(SEED)
        ListingTwinTestSupport.createAndRefresh(jdbc)
        YachtCapacityTestSupport.addColumns(jdbc)
        applyRepeatableLikeFlyway(jdbc)

        entityManagerFactory = buildEntityManagerFactory()
        entityManager = entityManagerFactory.createEntityManager()
        val repositories = JpaRepositoryFactory(entityManager)
        val locations = repositories.getRepository(LocationRepository::class.java)
        val regions = repositories.getRepository(RegionRepository::class.java)
        val countries = repositories.getRepository(CountryRepository::class.java)
        val exchangeRates = mock(ExchangeRateCalculationService::class.java)
        service =
            YachtQueryingService(
                entityManager,
                repositories.getRepository(YachtRepository::class.java),
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
        distribution = YachtDistributionService(entityManager, locations, countries, regions, PassThroughHeavyQueries, NoOpCacheManager())
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
        charterTypes: List<CharterType>? = null,
        start: LocalDate = S,
        end: LocalDate = E,
    ) = YachtSearchParamObject(
        locationIds = null,
        charterTypes = charterTypes,
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
        weeklyPrice = false,
        language = LanguageEnum.EN,
    )

    private fun signIn(vararg authorities: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone", null, authorities.map { SimpleGrantedAuthority(it) })
    }

    private fun search(
        charterTypes: List<CharterType>? = null,
        isAdmin: Boolean = false,
    ): Map<Long, YachtSearchResponseDto> = service.getYachts(params(charterTypes), "id", LanguageEnum.EN, 0, 50, isAdmin).content.associateBy { it.id }

    @Test
    fun `listing charter type - a gulet never reads BAREBOAT, every other boat keeps its partner type`() {
        assertEquals(
            mapOf(
                1L to BAREBOAT,
                2L to CREWED, // gulet tagged BAREBOAT only
                3L to CREWED, // gulet by model name
                4L to ALL_INCLUSIVE, // gulet: BAREBOAT dropped, ALL_INCLUSIVE left
                5L to CREWED,
                6L to BAREBOAT,
                7L to BAREBOAT,
                8L to CREWED,
                9L to BAREBOAT, // not a gulet: LEAST(BAREBOAT, CREWED) as before
                10L to BAREBOAT,
                11L to BAREBOAT,
                12L to BAREBOAT,
                13L to BAREBOAT,
                14L to BAREBOAT,
                15L to BAREBOAT,
                16L to BAREBOAT,
            ),
            search().mapValues { it.value.charterType },
        )
        // under a filter the card reads an asked type: the CREWED filter passes every row of gulet 4 (BAREBOAT +
        // ALL_INCLUSIVE), and the card says CREWED, not ALL_INCLUSIVE; boat 9 (not a gulet) reads its CREWED row
        val crewedCards = search(listOf(CREWED))
        assertEquals(CREWED, crewedCards.getValue(4).charterType)
        assertEquals(CREWED, crewedCards.getValue(9).charterType)
        assertEquals(CREWED, crewedCards.getValue(2).charterType)
        assertEquals(ALL_INCLUSIVE, search(listOf(ALL_INCLUSIVE)).getValue(4).charterType)
        assertEquals(ALL_INCLUSIVE, search(listOf(BAREBOAT, ALL_INCLUSIVE)).getValue(4).charterType)
    }

    @Test
    fun `charterType filter, its total and the facet counts - BAREBOAT never a gulet, CREWED every gulet`() {
        val bareboat = setOf(1L, 6L, 7L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L)
        val crewed = setOf(2L, 3L, 4L, 5L, 8L, 9L, 11L, 14L)
        assertEquals(bareboat, search(listOf(BAREBOAT)).keys)
        assertEquals(crewed, search(listOf(CREWED)).keys)
        assertEquals(setOf(4L), search(listOf(ALL_INCLUSIVE)).keys)
        assertEquals((1L..16L).toSet(), search(listOf(BAREBOAT, CREWED)).keys)
        assertEquals(bareboat.size.toLong(), service.getYachtSearchTotalCount(params(listOf(BAREBOAT))))
        assertEquals(crewed.size.toLong(), service.getYachtSearchTotalCount(params(listOf(CREWED))))

        val facets = distribution.getDistribution(startDate = S, endDate = E).byCharterType
        assertEquals(mapOf(BAREBOAT to bareboat.size.toLong(), CREWED to crewed.size.toLong(), ALL_INCLUSIVE to 1L), facets)
        // the other facets under a charter filter count the same boats as the listing
        assertEquals(crewed.size.toLong(), distribution.getDistribution(startDate = S, endDate = E, charterTypes = listOf(CREWED)).byVesselType.values.sum())
        assertEquals(bareboat.size.toLong(), distribution.getDistribution(startDate = S, endDate = E, charterTypes = listOf(BAREBOAT)).byVesselType.values.sum())
    }

    @Test
    fun `admin listing - Bareboat, Skippered or Crewed for the offer each card shows`() {
        signIn("SYSTEM_ADMIN")
        val cards = search(isAdmin = true).mapValues { it.value.offerCharter }
        val bareboat = OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.BAREBOAT)
        assertEquals(
            mapOf(
                1L to bareboat, // deposit row about skippers, optional skipper, last season's boat skipper: bareboat
                2L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.GULET), // gulet tagged BAREBOAT
                3L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.GULET),
                4L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.GULET),
                5L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_PRODUCT),
                6L to OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, "Skipper + food"),
                7L to OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, "One man crew (Caribbean)"),
                8L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_YACHT),
                9L to bareboat, // the boat page's offer of the week (the first round trip), insurance is not a skipper
                10L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.OBLIGATORY_CREW, "3 crew: 1 captain, 1 cook and 1 deckhand"),
                11L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_PRODUCT), // the round trip, not the one-way
                12L to bareboat, // its skipper is for other bases: never charged, not Skippered
                13L to OfferCharterDto(OfferCharterKind.SKIPPERED, OfferCharterBasis.OBLIGATORY_SKIPPER, "Skipper"),
                14L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.CREWED_PRODUCT), // the FREE offer, not the held one
                15L to OfferCharterDto(OfferCharterKind.CREWED, OfferCharterBasis.OBLIGATORY_CREW, "Skipper + Hostess"),
                16L to OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.OBLIGATORY_CREW_MEMBER, "Hostess"),
            ),
            cards,
        )
    }

    @Test
    fun `admin listing - a two-week card gets a pill only when both weeks read the same`() {
        signIn("SYSTEM_ADMIN")
        val cards =
            service
                .getYachts(params(start = S2, end = E2).copy(yachtIds = listOf(17L, 18L)), "id", LanguageEnum.EN, 0, 50, true)
                .content
                .associateBy { it.id }
        assertEquals(setOf(17L, 18L), cards.keys)
        cards.values.forEach { assertEquals(S2 to E2, it.offerDateFrom to it.offerDateTo, "tiled card ${it.id}") }
        assertEquals(OfferCharterDto(OfferCharterKind.BAREBOAT, OfferCharterBasis.BAREBOAT), cards.getValue(17).offerCharter)
        assertNull(cards.getValue(18).offerCharter) // bareboat week + skippered week: no single answer
    }

    @Test
    fun `public listing never carries the admin pill, not even as a key`() {
        // anonymous, a customer, a customer whose query still ran the admin lookup: the mapper gates again
        listOf(false, true).forEach { adminQuery ->
            listOf<Array<String>>(emptyArray(), arrayOf("USER")).forEach { who ->
                SecurityContextHolder.clearContext()
                if (who.isNotEmpty()) signIn(*who)
                val cards = search(isAdmin = adminQuery)
                assertEquals(16, cards.size)
                assertTrue(cards.values.all { it.offerCharter == null }, "offerCharter for ${who.toList()} / adminQuery=$adminQuery")
                val json = CapacityFixtures.mapper.writeValueAsString(cards.values)
                assertFalse(json.contains("offerCharter"), "offerCharter key in the public JSON")
            }
        }
        assertNull(search()[1]!!.offerCharter)
    }
}
