package hr.workspace.boat4you.domains.catalouge.successor

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.services.ListingTwinTestSupport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * 7.10.2026 (Mario): a retired partner boat's old URL names the live listing of the same boat (yacht_successor, V9_73),
 * so the sites can redirect instead of answering 404 (Bing: Lagoon 42 "Masterpiece" 4066 retired, live as 11681).
 * The real V9_73 table, the real V9_69/V9_70 listing-twin matview and the job's SQL on PostgreSQL.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtSuccessorComputeServiceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val SCHEMA =
            """
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255), country_code varchar(2),
                                   inland boolean NOT NULL DEFAULT false);
            CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false);
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name text);
            CREATE TABLE model (id bigint PRIMARY KEY, name text, manufacturer_id bigint);
            CREATE TABLE yacht (id bigint PRIMARY KEY, name text, agency_id bigint, entry_type text NOT NULL,
                                sys_active boolean NOT NULL DEFAULT true, location_id bigint, build_year integer,
                                model_id bigint, vessel_type text, length numeric);
            CREATE TABLE offer (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL,
                                date_to date NOT NULL, status text NOT NULL);
            """.trimIndent()

        // models: two partner systems keep "Lagoon 42" under two ids; "Dufour 390 GL" / "Dufour 390 Grand Large" likewise
        private const val LAGOON_42 = 1
        private const val LAGOON_42_OTHER_SOURCE = 2
        private const val DUFOUR_390_GL = 4
        private const val DUFOUR_390_GRAND_LARGE = 5

        // bases
        private const val DMARIN = 1
        private const val KASTELA = 2
        private const val KASTELA_OTHER_SPELLING = 3
        private const val ALIMOS = 4
        private const val LAKE = 5
        private const val SPLIT = 6

        // agencies
        private const val OWNER = 1
        private const val BROKER = 2
        private const val THIRD = 3
        private const val SWITCHED_OFF = 4
        private const val BLOCKED = 5
        private const val INQUIRY_ONLY = 6
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: YachtSuccessorComputeService

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(SCHEMA)
        ListingTwinTestSupport.createAndRefresh(jdbc)
        // V9_73 as Flyway runs it - twice: it must be idempotent
        val migration = ClassPathResource("db/migration/V9_73__yacht_successor.sql").inputStream.bufferedReader().readText()
        repeat(2) { jdbc.execute("BEGIN; $migration; COMMIT;") }
        service = YachtSuccessorComputeService(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute(
            """
            TRUNCATE offer, yacht, agency, location, model, manufacturer, yacht_twin_manual_pair, yacht_successor;
            INSERT INTO location (id, name, country_code, inland) VALUES
                (1, 'D-Marin Dalmacija Marina', 'HR', false), (2, 'Marina Kastela', 'HR', false),
                (3, 'Marina Kaštela', 'HR', false), (4, 'Alimos Marina', 'GR', false),
                (5, 'Marina di Navene', 'HR', true), (6, 'ACI Marina Split', 'HR', false);
            INSERT INTO agency (id, name, active, availability_blocked, inquiry_only) VALUES
                (1, 'Owner', true, false, false), (2, 'Broker', true, false, false), (3, 'Third', true, false, false),
                (4, 'Switched off', false, false, false), (5, 'Blocked', true, true, false),
                (6, 'Inquiry only', true, false, true);
            INSERT INTO manufacturer (id, name) VALUES (1, 'Lagoon'), (2, 'Dufour');
            INSERT INTO model (id, name, manufacturer_id) VALUES (1, 'Lagoon 42', 1), (2, 'Lagoon 42', 1),
                (3, 'Lagoon 46', 1), (4, 'Dufour 390 GL', 2), (5, 'Dufour 390 Grand Large', 2);
            """.trimIndent(),
        )
    }

    private fun yacht(
        id: Long,
        name: String,
        active: Boolean,
        agency: Int = OWNER,
        location: Int = DMARIN,
        year: Int = 2018,
        model: Int = LAGOON_42,
        length: Double? = 12.94,
    ) = jdbc.update(
        "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, location_id, build_year, model_id, vessel_type, length) " +
            "VALUES (?, ?, ?, 'EXTERNAL', ?, ?, ?, ?, 'CATAMARAN', ?)",
        id,
        name,
        agency,
        active,
        location,
        year,
        model,
        length,
    )

    /** A week to sell from next week on - what makes a copy the one the listings show (yacht_listing_twin). */
    private fun sellable(vararg yachts: Long) =
        yachts.forEach {
            val from = LocalDate.now().plusWeeks(1)
            jdbc.update(
                "INSERT INTO offer (yacht_id, date_from, date_to, status) VALUES (?, ?, ?, 'FREE')",
                it,
                java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(from.plusDays(7)),
            )
        }

    /** retired id -> successor, after a run like the job's. */
    private fun successors(): Map<Long, Long> {
        ListingTwinTestSupport.refresh(jdbc)
        service.recompute()
        return stored()
    }

    private fun stored(): Map<Long, Long> = jdbc.query("SELECT old_id, new_id FROM yacht_successor") { rs, _ -> rs.getLong(1) to rs.getLong(2) }.toMap()

    private fun lookup(id: Long): YachtSuccessor? =
        NamedParameterJdbcTemplate(jdbc)
            .query(YachtSuccessorLookup.SQL, mapOf("id" to id)) { rs, _ ->
                YachtSuccessorLookup.fromRow(arrayOf(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            }.firstOrNull()

    @Test
    fun `a retired listing names the live listing of the same boat - Masterpiece 4066 to 11681`() {
        // production 7.10.: same model, year and marina, another agency; the retired copy has no length
        yacht(4066, "Masterpiece", active = false, length = null)
        yacht(11681, "MASTERPIECE", active = true, agency = BROKER)

        successors() shouldBe mapOf(4066L to 11681L)
        lookup(4066) shouldBe YachtSuccessor(11681, "lagoon-42-masterpiece-11681")
        lookup(11681) shouldBe null
    }

    @Test
    fun `never another build year or another country`() {
        yacht(10, "Zoa", active = false)
        yacht(11, "Zoa", active = true, agency = BROKER, year = 2019)
        yacht(12, "Zoa", active = true, agency = THIRD, location = ALIMOS)

        successors() shouldBe emptyMap()
    }

    @Test
    fun `a model kept under another id matches only on its first word and a length within 0,3 m`() {
        yacht(20, "Mimi", active = false, model = DUFOUR_390_GL, length = 11.93)
        yacht(21, "Mimi", active = true, agency = BROKER, model = DUFOUR_390_GRAND_LARGE, length = 11.93)
        yacht(22, "Moderato", active = false, model = DUFOUR_390_GL, length = 11.93)
        yacht(23, "Moderato", active = true, agency = BROKER, model = DUFOUR_390_GRAND_LARGE, length = 12.30)
        yacht(24, "Joy", active = false, model = DUFOUR_390_GL, length = null)
        yacht(25, "Joy", active = true, agency = BROKER, model = DUFOUR_390_GRAND_LARGE, length = 11.94)
        // same first word, another model: never without the length check
        yacht(26, "Brattia", active = false, model = 3, length = 13.99)
        yacht(27, "Brattia", active = true, agency = BROKER, model = LAGOON_42, length = 12.8)

        successors() shouldBe mapOf(20L to 21L)
    }

    @Test
    fun `two copies of one boat from two partner systems name the copy the listings show`() {
        // 101 (MMK) and 102 (NauSys, the same marina spelled with a diacritic) are one boat: the listings show 101
        yacht(100, "Krista", active = false)
        yacht(101, "Krista", active = true, agency = BROKER, location = KASTELA)
        yacht(102, "Krista", active = true, agency = THIRD, location = KASTELA_OTHER_SPELLING)
        sellable(101, 102)
        // the retired copy's only match (112) is itself hidden behind another copy (111, another model id, 0.44 m
        // shorter: no match for 110 itself) - the successor is the copy shown
        yacht(110, "Ecstasea", active = false, length = 12.94)
        yacht(111, "Ecstasea", active = true, agency = BROKER, model = LAGOON_42_OTHER_SOURCE, length = 12.50)
        yacht(112, "Ecstasea", active = true, agency = THIRD, length = 12.94)
        sellable(111, 112)

        successors() shouldBe mapOf(100L to 101L, 110L to 111L)
    }

    @Test
    fun `the copy shown replaces a hidden copy only with the same year and while it is served`() {
        // partners disagree by a year: the listings pair 901 (2019) and 902 (2018) and show 901 - not this boat's year
        yacht(900, "Konstantinos", active = false)
        yacht(901, "Konstantinos", active = true, agency = BROKER, year = 2019)
        yacht(902, "Konstantinos", active = true, agency = THIRD)
        sellable(901, 902)
        successors() shouldBe mapOf(900L to 902L)

        // the copy shown was switched off after the last matview refresh: the live copy itself, never a 1502 page
        yacht(910, "Galini", active = false)
        yacht(911, "Galini", active = true, agency = BROKER)
        yacht(912, "Galini", active = true, agency = THIRD)
        sellable(911, 912)
        ListingTwinTestSupport.refresh(jdbc)
        jdbc.update("UPDATE yacht SET sys_active = false WHERE id = 911")
        service.recompute()
        stored() shouldBe mapOf(900L to 902L, 910L to 912L, 911L to 912L)
    }

    @Test
    fun `different live boats of one name - the one at the old base, otherwise none`() {
        // 1 m apart, so not one boat for the listings either
        yacht(200, "Aristofanis", active = false, location = SPLIT)
        yacht(201, "Aristofanis", active = true, agency = BROKER, location = SPLIT, length = 13.96)
        yacht(202, "Aristofanis", active = true, agency = THIRD, location = KASTELA, length = 12.96)
        // the same marina under another location id counts as the old base
        yacht(210, "Omnia", active = false, location = KASTELA)
        yacht(211, "Omnia", active = true, agency = BROKER, location = KASTELA_OTHER_SPELLING, length = 13.96)
        yacht(212, "Omnia", active = true, agency = THIRD, location = SPLIT, length = 12.96)
        // neither at the old base: a guess would be worse than the 404
        yacht(220, "Oreo", active = false, location = DMARIN)
        yacht(221, "Oreo", active = true, agency = BROKER, location = SPLIT, length = 13.96)
        yacht(222, "Oreo", active = true, agency = THIRD, location = KASTELA, length = 12.96)
        sellable(201, 202, 211, 212, 221, 222)

        successors() shouldBe mapOf(200L to 201L, 210L to 211L)
    }

    @Test
    fun `a chain through a retired copy reaches the live one`() {
        // 300 has no length, so it cannot match 302 (another model id) directly; the retired 301 (same model as 300,
        // a length within 0.3 m of 302) links them
        yacht(300, "Petra M", active = false, model = DUFOUR_390_GL, length = null)
        yacht(301, "Petra M", active = false, agency = BROKER, model = DUFOUR_390_GL, length = 11.93)
        yacht(302, "Petra M", active = true, agency = THIRD, model = DUFOUR_390_GRAND_LARGE, length = 11.95)

        successors() shouldBe mapOf(300L to 302L, 301L to 302L)
        jdbc.queryForObject("SELECT count(*) FROM yacht_successor WHERE new_id = 302", Int::class.java) shouldBe 2
    }

    @Test
    fun `retired copies pointing at each other end without a successor`() {
        yacht(400, "Stavento", active = false)
        yacht(401, "Stavento", active = false, agency = BROKER)
        yacht(402, "Stavento", active = false, agency = THIRD, length = 12.90)
        // a chain longer than the hop limit: every copy under its own model id, each 0.25 m longer than the one before,
        // so a copy matches only its neighbours (410 has no length: only 411, its own model)
        jdbc.execute(
            "INSERT INTO model (id, name, manufacturer_id) VALUES (7, 'Dufour 390', 2), (8, 'Dufour 390 G.L.', 2), " +
                "(9, 'Dufour 390 Grand-Large', 2), (10, 'Dufour 390 GL Owner', 2), (11, 'Dufour 390 GL 3 cab', 2)",
        )
        yacht(410, "Pathfinder", active = false, model = 7, length = null)
        yacht(411, "Pathfinder", active = false, model = 7, length = 12.0)
        yacht(412, "Pathfinder", active = false, model = 8, length = 12.25)
        yacht(413, "Pathfinder", active = false, model = 9, length = 12.5)
        yacht(414, "Pathfinder", active = false, model = 10, length = 12.75)
        yacht(415, "Pathfinder", active = true, agency = BROKER, model = 11, length = 13.0)

        val result = successors()
        result.keys.filter { it in 400..402 } shouldBe emptyList()
        // 410 -> 411 -> 412 -> 413 -> 414 is four hops, 415 would be the fifth
        result[410] shouldBe null
        result[411] shouldBe 415L
        result[414] shouldBe 415L
    }

    @Test
    fun `a boat page that is not served is no successor - an inquiry-only one is`() {
        yacht(500, "Dear John", active = false)
        yacht(501, "Dear John", active = true, agency = SWITCHED_OFF)
        yacht(510, "Bellagio", active = false)
        yacht(511, "Bellagio", active = true, agency = BLOCKED)
        yacht(520, "Brioni", active = false)
        yacht(521, "Brioni", active = true, agency = BROKER, location = LAKE)
        // the chain does not run through a live but hidden copy either
        yacht(530, "Tonina", active = false, model = DUFOUR_390_GL, length = null)
        yacht(531, "Tonina", active = true, agency = SWITCHED_OFF, model = DUFOUR_390_GL, length = 11.93)
        yacht(532, "Tonina", active = true, agency = BROKER, model = DUFOUR_390_GRAND_LARGE, length = 11.95)
        // inquiry-only boats keep their page (200)
        yacht(540, "Vaiana", active = false)
        yacht(541, "Vaiana", active = true, agency = INQUIRY_ONLY)

        successors() shouldBe mapOf(540L to 541L)
    }

    @Test
    fun `a name that is only the model or a placeholder never matches`() {
        yacht(600, "Lagoon 42", active = false)
        yacht(601, "LAGOON 42", active = true, agency = BROKER)
        yacht(610, "No name", active = false)
        yacht(611, "No name", active = true, agency = BROKER)
        // a short real name does
        yacht(620, "K3", active = false)
        yacht(621, "K3", active = true, agency = BROKER)

        successors() shouldBe mapOf(620L to 621L)
    }

    @Test
    fun `each run replaces every row - a run that names none keeps the stored rows`() {
        yacht(700, "Dear John", active = false)
        yacht(701, "Dear John", active = true, agency = BROKER)
        successors() shouldBe mapOf(700L to 701L)

        // 701 switched off too: nothing to name -> broken input, previous rows kept, the lookup checks the target live
        jdbc.update("UPDATE yacht SET sys_active = false WHERE id = 701")
        ListingTwinTestSupport.refresh(jdbc)
        service.recompute().stored shouldBe false
        stored() shouldBe mapOf(700L to 701L)
        lookup(700) shouldBe null

        // the boat is live again under a new id: the old row goes, the new ones come in one transaction
        yacht(702, "Dear John", active = true, agency = THIRD)
        successors() shouldBe mapOf(700L to 702L, 701L to 702L)
        service.isEmpty() shouldBe false
    }

    @Test
    fun `the lookup re-checks that the successor is still served`() {
        yacht(800, "Blue Navy", active = false)
        yacht(801, "Blue Navy", active = true, agency = BROKER)
        successors() shouldBe mapOf(800L to 801L)
        lookup(800) shouldBe YachtSuccessor(801, "lagoon-42-blue-navy-801")

        jdbc.update("UPDATE agency SET availability_blocked = true WHERE id = ?", BROKER)
        lookup(800) shouldBe null
        jdbc.update("UPDATE agency SET availability_blocked = false, active = false WHERE id = ?", BROKER)
        lookup(800) shouldBe null
        jdbc.update("UPDATE agency SET active = true WHERE id = ?", BROKER)
        jdbc.update("UPDATE location SET inland = true WHERE id = ?", DMARIN)
        lookup(800) shouldBe null
    }
}
