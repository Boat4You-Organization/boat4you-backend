package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.equipment.EquipmentAliases
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMappingRepository
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
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
 * The amenity filter counts distinct CODES (equipment audit 8.10.2026): a yacht with two rows of one code (two partner
 * items linking to it, e.g. "Refrigerator" + "Fridge on flybridge", or MMK free-text rows after the -1 split) used to
 * count 2 and fall out of a one-amenity filter - 2,039 active (yacht, filter) pairs on prod. An old merged id
 * (refrigerator) is canonicalised before the search, so it filters like fridge. Real Hibernate + Postgres on the real
 * R__1_03 matview (recipe of YachtSearchDatedOfferTest).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSearchAmenityFilterTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val FRIDGE = 14L
        private const val GENERATOR = 29L
        private const val REFRIGERATOR = 90L

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
                                match_keys text, filter_order smallint, merged_into_id bigint);
            CREATE TABLE yacht_equipment (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, equipment_id bigint,
                                name text, external_id bigint, highlight boolean NOT NULL DEFAULT false,
                                quantity numeric, comment text);
            CREATE TABLE yacht_content_modified (yacht_id bigint PRIMARY KEY, modified_at timestamptz NOT NULL);
            """.trimIndent()

        private val SEED: String =
            buildString {
                appendLine("INSERT INTO location (id, display_name, country_code) VALUES (1, 'Marina Kastela | Split', 'HR');")
                appendLine("INSERT INTO agency (id, name, active, availability_blocked, recommended) VALUES (1, 'Agency', true, false, false);")
                (1..3).forEach { id ->
                    appendLine(
                        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, vessel_type, location_id) " +
                            "VALUES ($id, 'Yacht $id', 1, 'EXTERNAL', true, 'SAILING_YACHT', 1);",
                    )
                    appendLine("INSERT INTO yacht_charter_type (id, yacht_id, type) VALUES ($id, $id, 'BAREBOAT');")
                    appendLine(
                        "INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, " +
                            "ext_base_price, broker_commission, deposit, status) VALUES ($id, $id, 1, 1, DATE '$S', DATE '$E', " +
                            "${2000 + id}, ${2000 + id}, 0, 1000, 'FREE');",
                    )
                }
                appendLine(
                    "INSERT INTO equipment (id, name, label_code, category, match_keys, filter_order, merged_into_id) VALUES " +
                        "($FRIDGE, 'Fridge', 'fridge', 'GALLEY', 'token-match:fridge', 206, NULL), " +
                        "($GENERATOR, 'Generator', 'generator', 'YACHT_ELECTRICS', 'token-match:generator', 102, NULL), " +
                        "($REFRIGERATOR, 'Refrigerator', 'refrigerator', 'GALLEY', '', NULL, $FRIDGE);",
                )
                // yacht 1: "Refrigerator" + "Fridge on flybridge" (two rows, one code) and a generator; yacht 2: one
                // fridge; yacht 3: nothing
                appendLine(
                    "INSERT INTO yacht_equipment (id, yacht_id, equipment_id, name, external_id) VALUES " +
                        "(1, 1, $FRIDGE, 'Refrigerator', 37), (2, 1, $FRIDGE, 'Fridge on flybridge', -1), " +
                        "(3, 1, $GENERATOR, 'Generator', 3), (4, 2, $FRIDGE, 'Fridge', 37), (5, 3, NULL, 'Yanmar', -1);",
                )
            }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager
    private val yachtMapper: YachtMapper = mock(YachtMapper::class.java)
    private lateinit var service: YachtQueryingService

    private val catalogue =
        listOf(FRIDGE to null, GENERATOR to null, REFRIGERATOR to FRIDGE).map { (id, mergedInto) ->
            Equipment().apply {
                this.id = id
                labelCode = "e$id"
                category = CategoryEnum.GALLEY
                matchKeys = ""
                mergedIntoId = mergedInto
            }
        }
    private val aliases =
        EquipmentAliases(mock(EquipmentRepository::class.java) { inv -> if (inv.method.name == "findAllByOrderByIdAsc") catalogue else null })

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
        // the real V9_74 (catalogue not seeded here: the table comes empty) + one explicit link to read back over JPA
        jdbcTemplate.execute("CREATE TABLE external_system (id serial PRIMARY KEY, name varchar(50) NOT NULL); INSERT INTO external_system VALUES (1, 'MMK')")
        val v974 = ClassPathResource("db/migration/V9_74__equipment_alias_and_partner_mapping.sql").inputStream.bufferedReader().readText()
        jdbcTemplate.execute("BEGIN; $v974; COMMIT;")
        jdbcTemplate.execute(
            "INSERT INTO partner_equipment_mapping (external_system_id, partner_item_id, partner_name_norm, equipment_id, note) " +
                "VALUES (1, -1, 'mini bar', $FRIDGE, 'test')",
        )
        ListingTwinTestSupport.createAndRefresh(jdbcTemplate)
        YachtCapacityTestSupport.addColumns(jdbcTemplate)
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

    private fun params(amenities: List<Long>?): YachtSearchParamObject =
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
            startDate = S,
            endDate = E,
            minWc = null,
            maxWc = null,
            minEnginePower = null,
            maxEnginePower = null,
            currency = CurrencyEnum.EUR,
            amenities = amenities,
            services = null,
            yachtIds = null,
            weeklyPrice = false,
            language = LanguageEnum.EN,
        )

    /** Yacht ids of one search, read off the mapper calls (one toDto per row). */
    private fun search(amenities: List<Long>?): Set<Long> {
        clearInvocations(yachtMapper)
        service.getYachts(params(amenities), "", LanguageEnum.EN, 0, 50, isAdmin = false)
        return mockingDetails(yachtMapper).invocations.map { it.getArgument<YachtSearchSelectResult>(0).id }.toSet()
    }

    @Test
    fun `a yacht with two rows of one code passes a one-amenity filter`() {
        assertEquals(setOf(1L, 2L, 3L), search(null))
        assertEquals(setOf(1L, 2L), search(listOf(FRIDGE)))
        assertEquals(setOf(1L), search(listOf(FRIDGE, GENERATOR)))
        assertEquals(setOf(1L), search(listOf(GENERATOR)))
    }

    @Test
    fun `catalogue queries - id order for the matcher, alias rows off the admin page, explicit links over JPA`() {
        val repositories = JpaRepositoryFactory(entityManager)
        val equipment = repositories.getRepository(EquipmentRepository::class.java)
        assertEquals(listOf(FRIDGE, GENERATOR, REFRIGERATOR), equipment.findAllByOrderByIdAsc().map { it.id })
        assertEquals(FRIDGE, equipment.findAllByOrderByIdAsc().last().mergedIntoId)
        assertEquals(listOf(FRIDGE, GENERATOR), equipment.findAllByMergedIntoIdIsNull(PageRequest.of(0, 10, Sort.by("id"))).content.map { it.id })
        val mapping = repositories.getRepository(PartnerEquipmentMappingRepository::class.java).findAllForResolver().single()
        assertEquals(listOf<Any?>(1, -1L, "mini bar", FRIDGE), listOf(mapping.externalSystemId, mapping.partnerItemId, mapping.partnerNameNorm, mapping.equipmentId))
    }

    @Test
    fun `an old merged id filters like its canonical code once canonicalised`() {
        assertEquals(listOf(FRIDGE), aliases.canonicalIds(listOf(REFRIGERATOR)))
        assertEquals(listOf(FRIDGE, GENERATOR), aliases.canonicalIds(listOf(REFRIGERATOR, GENERATOR, FRIDGE)))
        assertEquals(search(listOf(FRIDGE)), search(aliases.canonicalIds(listOf(REFRIGERATOR))))
        assertEquals(emptySet(), search(listOf(REFRIGERATOR)), "no row is left on the alias itself")
        assertEquals(null, aliases.canonicalIds(null))
    }
}
