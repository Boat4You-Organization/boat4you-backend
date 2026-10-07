package hr.workspace.boat4you.domains.catalouge.jpa

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * Review 7.10.2026: the twin-canonical pick (GET /public/yachts/{id} of a pilot / manual twin group) only names a copy
 * whose boat page is served. A retired copy that still had FREE weeks used to win: the live copy's page turned into the
 * retired one's 1502, whose successor (yacht_successor, V9_73) is the live copy - the site would redirect it to itself.
 * The repository's real SQL on PostgreSQL.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtTwinCanonicalPickTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>("postgres:17-alpine").apply { withDatabaseName("boat4you_db") }

        private const val ACTIVE = 1L
        private const val SWITCHED_OFF = 2L
        private const val BLOCKED = 3L
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private val today: LocalDate = LocalDate.now()

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
        jdbc.execute(
            """
            CREATE TABLE agency (id bigint PRIMARY KEY, active boolean, availability_blocked boolean NOT NULL DEFAULT false);
            CREATE TABLE yacht (id bigint PRIMARY KEY, agency_id bigint, entry_type text NOT NULL, sys_active boolean,
                                commision_perc numeric, commision numeric);
            CREATE TABLE offer (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, status text NOT NULL,
                                date_from date NOT NULL, client_price numeric);
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute(
            "TRUNCATE offer, yacht, agency; " +
                "INSERT INTO agency (id, active, availability_blocked) VALUES (1, true, false), (2, false, false), (3, true, true)",
        )
    }

    private fun yacht(
        id: Long,
        active: Boolean = true,
        agency: Long = ACTIVE,
        freeWeeks: Int,
    ) {
        jdbc.update("INSERT INTO yacht (id, agency_id, entry_type, sys_active, commision_perc) VALUES (?, ?, 'EXTERNAL', ?, 20)", id, agency, active)
        repeat(freeWeeks) { week ->
            jdbc.update(
                "INSERT INTO offer (yacht_id, status, date_from, client_price) VALUES (?, 'FREE', ?, 3000)",
                id,
                java.sql.Date.valueOf(today.plusWeeks(week + 1L)),
            )
        }
    }

    private fun pick(
        sql: String,
        vararg ids: Long,
    ): Long? =
        NamedParameterJdbcTemplate(jdbc)
            .queryForList(sql, mapOf("ids" to ids.toList(), "today" to today), Long::class.java)
            .firstOrNull()

    @Test
    fun `a retired copy or one of a switched-off or blocked agency is never canonical`() {
        // 7576 retired with the fullest calendar, 9431 live: the live one is canonical
        yacht(7576, active = false, freeWeeks = 10)
        yacht(9431, freeWeeks = 2)
        yacht(6047, agency = SWITCHED_OFF, freeWeeks = 8)
        listOf(YachtTwinRepository.PICK_CANONICAL_BY_MARGIN_SQL, YachtTwinRepository.PICK_CANONICAL_BY_COVERAGE_SQL).forEach { sql ->
            pick(sql, 7576, 9431, 6047) shouldBe 9431L
        }

        // the manual pair: only a blocked-agency copy left with free weeks besides a retired one - no canonical, the
        // requested id stays (YachtTwinCanonicalService keeps it)
        yacht(481, active = false, freeWeeks = 5)
        yacht(13163, agency = BLOCKED, freeWeeks = 9)
        pick(YachtTwinRepository.PICK_CANONICAL_BY_COVERAGE_SQL, 481, 13163) shouldBe null
    }

    @Test
    fun `among served copies the rules are unchanged - margin first, or coverage first`() {
        jdbc.update("INSERT INTO agency (id, active, availability_blocked) VALUES (4, true, false)")
        yacht(10, freeWeeks = 3)
        yacht(11, agency = 4, freeWeeks = 5)
        jdbc.update("UPDATE yacht SET commision_perc = 40 WHERE id = 10")
        pick(YachtTwinRepository.PICK_CANONICAL_BY_MARGIN_SQL, 10, 11) shouldBe 10L
        pick(YachtTwinRepository.PICK_CANONICAL_BY_COVERAGE_SQL, 10, 11) shouldBe 11L
    }
}
