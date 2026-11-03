package hr.workspace.boat4you.domains.external.mmk

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * V9_117 after V9_116 (3.11.2026): the old-base round trips with no home-base twin (14/21/28-night packages, odd
 * weeks) of 18643 / 18707 / 18753 go to UNAVAILABLE + ROUTE_GONE too, MMK having confirmed it sends none of them;
 * everything the guards protect stays. Production column types, the real V9_82 flip log, both files as Flyway runs them.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteGoneAgency1579RestMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val SCHEMA =
            """
            CREATE TABLE yacht (id bigint PRIMARY KEY, location_id bigint);
            CREATE TABLE offer (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL, date_to date NOT NULL,
                                status varchar(31) NOT NULL, ext_status varchar(50), client_price numeric NOT NULL,
                                product varchar(31) NOT NULL, location_from bigint, location_to bigint);
            CREATE TABLE reservation_flow (id bigint PRIMARY KEY, offer_id bigint);
            CREATE TABLE external_reservations (id bigserial PRIMARY KEY, yacht_id bigint, status varchar(31) NOT NULL,
                                                date_from date NOT NULL, date_to date NOT NULL);
            CREATE TABLE reservation_extras (id bigint PRIMARY KEY);
            """.trimIndent()

        private const val PROPRIANO = 813L
        private const val PULA = 1932L
        private const val MONTENEGRO = 1466L
        private const val OTHER_YACHT = 999L
        private const val OLBIA = 30L
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate

    private fun offer(
        id: Long,
        yacht: Long,
        weeksFromToday: Int,
        nights: Int,
        from: Long,
        to: Long = from,
        status: String = "FREE",
    ) = jdbc.update(
        "INSERT INTO offer VALUES (?, ?, CURRENT_DATE + ?, CURRENT_DATE + ? + ?, ?, ?, ?, 'BAREBOAT', ?, ?)",
        id,
        yacht,
        weeksFromToday * 7,
        weeksFromToday * 7,
        nights,
        status,
        status,
        3000 + id,
        from,
        to,
    )

    private fun migration(file: String) = ClassPathResource("db/migration/$file").getContentAsString(Charsets.UTF_8)

    @BeforeAll
    fun migrate() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(SCHEMA)
        jdbc.execute("BEGIN; ${migration("V9_82__real_price_evidence.sql")}; COMMIT;")
        jdbc.execute("INSERT INTO yacht VALUES (18643, $PROPRIANO), (18707, 958), (18753, $PULA), ($OTHER_YACHT, $PROPRIANO)")

        offer(100, 18643, 1, 7, PULA) // V9_116: a week with a home-base twin
        offer(200, 18643, 1, 7, PROPRIANO)
        offer(120, 18643, 2, 14, PULA) // V9_117: a 14-night package with no home-base twin
        offer(121, 18643, 4, 28, PULA, status = "OPTION") // a partner option without any hold
        offer(122, 18753, 2, 14, MONTENEGRO)
        offer(123, 18707, 6, 7, MONTENEGRO) // a single week with no twin

        offer(130, 18643, 8, 14, PROPRIANO) // the home base itself
        offer(131, 18643, 9, 14, PULA, status = "OPTION_WAITING") // pre-reserved: never touched
        offer(132, 18643, 10, 14, PULA)
        jdbc.update("INSERT INTO reservation_flow VALUES (1, 132)") // someone is booking that row
        offer(133, 18643, 11, 14, PULA)
        jdbc.update("INSERT INTO external_reservations (yacht_id, status, date_from, date_to) VALUES (18643, 'OPTION', CURRENT_DATE + 79, CURRENT_DATE + 80)")
        offer(134, 18643, -3, 14, PULA) // in the past
        offer(135, OTHER_YACHT, 2, 14, PULA) // a yacht outside the three
        offer(136, 18707, 7, 7, 958, to = MONTENEGRO) // a one-way the partner still sends
        offer(137, 18643, 12, 14, OLBIA) // a round trip of a base that is neither home nor the known old base

        // as Flyway runs them, V9_117 twice: a second pass finds nothing left to flip
        jdbc.execute("BEGIN; ${migration("V9_116__route_gone_agency_1579.sql")}; COMMIT;")
        repeat(2) { jdbc.execute("BEGIN; ${migration("V9_117__route_gone_agency_1579_rest.sql")}; COMMIT;") }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `the old-base round trips without a twin go too, everything guarded stays`() {
        jdbc.queryForList("SELECT id FROM offer WHERE ext_status = 'ROUTE_GONE' AND status = 'UNAVAILABLE' ORDER BY id", Long::class.java) shouldBe
            listOf(100L, 120L, 121L, 122L, 123L)
        jdbc.queryForObject("SELECT count(*) FROM offer WHERE ext_status IS DISTINCT FROM status AND id NOT IN (100, 120, 121, 122, 123)", Int::class.java) shouldBe 0
    }

    @Test
    fun `each flip is logged once with what an undo needs`() {
        jdbc.queryForList(
            "SELECT offer_id || ' ' || old_status || ' ' || old_client_price FROM partner_offer_flip_log " +
                "WHERE job = 'MIGRATION' AND reason = 'ROUTE_GONE' AND new_status = 'UNAVAILABLE' ORDER BY offer_id",
            String::class.java,
        ) shouldBe listOf("100 FREE 3100", "120 FREE 3120", "121 OPTION 3121", "122 FREE 3122", "123 FREE 3123")
    }
}
