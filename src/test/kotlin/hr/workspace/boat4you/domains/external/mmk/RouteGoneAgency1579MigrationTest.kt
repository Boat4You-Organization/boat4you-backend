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
 * V9_116 (3.11.2026): the stale round-trip rows of a former base on 18643 / 18707 / 18753 go to UNAVAILABLE +
 * ROUTE_GONE with a flip-log row each, and nothing else moves. Production column types, the real V9_82 flip log, the
 * migration file run as Flyway runs it.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteGoneAgency1579MigrationTest {
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

        // bases: Propriano (18643's home now), Pula (18643's old base, 18707's home now), Montenegro (18707's old base)
        private const val PROPRIANO = 813L
        private const val PULA = 1932L
        private const val MONTENEGRO = 1466L
        private const val OTHER_YACHT = 999L
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate

    private fun offer(
        id: Long,
        yacht: Long,
        weeksFromToday: Int,
        from: Long,
        to: Long = from,
        status: String = "FREE",
        product: String = "BAREBOAT",
    ) = jdbc.update(
        "INSERT INTO offer VALUES (?, ?, CURRENT_DATE + ?, CURRENT_DATE + ? + 7, ?, ?, ?, ?, ?, ?)",
        id,
        yacht,
        weeksFromToday * 7,
        weeksFromToday * 7,
        status,
        status,
        3000 + id,
        product,
        from,
        to,
    )

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
        jdbc.execute("BEGIN; ${ClassPathResource("db/migration/V9_82__real_price_evidence.sql").getContentAsString(Charsets.UTF_8)}; COMMIT;")
        jdbc.execute("INSERT INTO yacht VALUES (18643, $PROPRIANO), (18707, $PULA), (18753, $PULA), ($OTHER_YACHT, $PROPRIANO)")

        // flipped: an old-base row with a newer home-base row for the same dates and product
        offer(100, 18643, 1, PULA)
        offer(200, 18643, 1, PROPRIANO)
        offer(101, 18643, 2, PULA, status = "OPTION") // a partner option without any hold
        offer(201, 18643, 2, PROPRIANO)
        offer(102, 18707, 1, MONTENEGRO)
        offer(202, 18707, 1, PULA)

        // kept
        offer(103, 18643, 3, PULA) // no home-base row for those dates
        offer(104, 18643, 4, PULA)
        offer(204, 18643, 4, PROPRIANO, status = "UNAVAILABLE") // the home-base row itself is not on sale
        offer(105, 18643, 5, PULA, status = "OPTION_WAITING") // pre-reserved: never touched
        offer(205, 18643, 5, PROPRIANO)
        offer(106, 18643, 6, PULA)
        offer(206, 18643, 6, PROPRIANO)
        jdbc.update("INSERT INTO reservation_flow VALUES (1, 106)") // someone is booking that row
        offer(107, 18643, 7, PULA)
        offer(207, 18643, 7, PROPRIANO)
        jdbc.update("INSERT INTO external_reservations (yacht_id, status, date_from, date_to) VALUES (18643, 'OPTION', CURRENT_DATE + 50, CURRENT_DATE + 52)")
        offer(108, 18643, -1, PULA) // in the past
        offer(208, 18643, -1, PROPRIANO)
        offer(109, OTHER_YACHT, 1, PULA) // a yacht outside the three
        offer(209, OTHER_YACHT, 1, PROPRIANO)
        offer(110, 18643, 8, PULA, to = PROPRIANO) // one-way
        offer(210, 18643, 8, PROPRIANO)
        offer(211, 18643, 9, PROPRIANO)
        offer(300, 18643, 9, PULA) // the old-base row is the newer one
        offer(112, 18643, 10, PULA, product = "CREWED") // another product
        offer(212, 18643, 10, PROPRIANO)

        val migration = ClassPathResource("db/migration/V9_116__route_gone_agency_1579.sql").getContentAsString(Charsets.UTF_8)
        // as Flyway runs it - twice: a second pass finds nothing left to flip
        repeat(2) { jdbc.execute("BEGIN; $migration; COMMIT;") }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `only the stale old-base rows go to UNAVAILABLE ROUTE_GONE`() {
        jdbc.queryForList("SELECT id FROM offer WHERE ext_status = 'ROUTE_GONE' ORDER BY id", Long::class.java) shouldBe
            listOf(100L, 101L, 102L)
        jdbc.queryForList("SELECT DISTINCT status FROM offer WHERE ext_status = 'ROUTE_GONE'", String::class.java) shouldBe
            listOf("UNAVAILABLE")
        jdbc.queryForObject("SELECT count(*) FROM offer WHERE ext_status IS DISTINCT FROM status AND id NOT IN (100, 101, 102)", Int::class.java) shouldBe 0
    }

    @Test
    fun `each flip is logged once with what an undo needs`() {
        jdbc.queryForList(
            "SELECT offer_id || ' ' || yacht_id || ' ' || job || ' ' || reason || ' ' || external_system_id || ' ' || old_status || ' ' || " +
                "old_ext_status || ' ' || old_client_price || ' ' || new_status || ' ' || (decided_on = CURRENT_DATE) || ' ' || " +
                "(run_id IS NULL) FROM partner_offer_flip_log ORDER BY offer_id",
            String::class.java,
        ) shouldBe
            listOf(
                "100 18643 MIGRATION ROUTE_GONE 1 FREE FREE 3100 UNAVAILABLE true true",
                "101 18643 MIGRATION ROUTE_GONE 1 OPTION OPTION 3101 UNAVAILABLE true true",
                "102 18707 MIGRATION ROUTE_GONE 1 FREE FREE 3102 UNAVAILABLE true true",
            )
    }
}
