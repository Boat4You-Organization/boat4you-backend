package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real R__1_03_yacht_search_view.sql and the real `REFRESH MATERIALIZED VIEW
 * CONCURRENTLY` (via [YachtSearchViewRefresher]) against a throw-away PostgreSQL container.
 *
 * Why no Spring context / Flyway: the versioned history cannot be replayed on an empty
 * database (V9_18 and later data migrations assume prod rows — pre-existing, and the
 * repo's ReservationFlowRepositoryTest never ran into it because it has no @Test). So the
 * test creates a minimal schema with exactly the columns the view reads and applies the
 * repeatable itself, in one transaction like Flyway does.
 *
 * Pool size 1 so `SHOW work_mem` after a refresh necessarily hits the connection the
 * refresher used (proves the RESET in `finally`); auto-commit like production.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YachtSearchViewRefresherTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        /** Index set of R__1_03 since 2.9.2026 = the previous set minus offer_status_idx and agency_id_idx. */
        val EXPECTED_INDEXES =
            setOf(
                "yacht_search_view_row_uid_uidx",
                "yacht_search_view_vessel_type_idx",
                "yacht_search_view_location_from_idx",
                "yacht_search_view_location_to_idx",
                "yacht_search_view_id_idx",
                "yacht_search_view_dates_idx",
                "yacht_search_view_client_price_idx",
                "yacht_search_view_country_from_idx",
                "yacht_search_view_country_to_idx",
            )

        /** Only the columns R__1_03_yacht_search_view.sql reads. */
        val MINIMAL_SCHEMA =
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
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var refresher: YachtSearchViewRefresher

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 1
            }
        jdbcTemplate = JdbcTemplate(dataSource)
        refresher = YachtSearchViewRefresher(jdbcTemplate)
        jdbcTemplate.execute(MINIMAL_SCHEMA)
        applyRepeatableLikeFlyway()
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    /** The whole R__1_03 file on one connection in one transaction — exactly what Flyway does. */
    private fun applyRepeatableLikeFlyway() {
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

    private fun indexNames(): Set<String> =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'yacht_search_view'",
            String::class.java,
        ).toSet()

    private fun buildLeftovers(): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM pg_class WHERE relname LIKE '%\\_build'", Int::class.java)!!

    private fun matviewCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM yacht_search_view", Int::class.java)!!

    private fun show(guc: String): String = jdbcTemplate.queryForObject("SHOW $guc", String::class.java)!!

    @Test
    @Order(1)
    fun `repeatable leaves a matview with the expected final index names and no _build leftovers`() {
        assertEquals(
            "m",
            jdbcTemplate.queryForObject("SELECT relkind::text FROM pg_class WHERE relname = 'yacht_search_view'", String::class.java),
        )
        assertEquals(EXPECTED_INDEXES, indexNames())
        assertEquals(0, buildLeftovers())
        val uniqueRowUid =
            jdbcTemplate.queryForObject(
                "SELECT indisunique FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'yacht_search_view_row_uid_uidx'",
                Boolean::class.java,
            )
        assertTrue(uniqueRowUid == true, "row_uid index must stay UNIQUE — REFRESH CONCURRENTLY requires it")
        assertTrue(
            jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('boat4you_app', 'public.yacht_search_view', 'SELECT')",
                Boolean::class.java,
            ) == true,
            "GRANT SELECT must land on the swapped-in relation",
        )
    }

    @Test
    @Order(2)
    fun `refresh runs CONCURRENTLY, surfaces committed source writes and resets the session GUCs`() {
        val defaultWorkMem = show("work_mem")
        val defaultLockTimeout = show("lock_timeout")
        val defaultJit = show("jit")

        assertEquals(0, matviewCount())
        refresher.refresh()
        assertEquals(0, matviewCount())

        seedOneExternalOffer()
        assertEquals(0, matviewCount(), "matview is a snapshot — new offer invisible before refresh")
        refresher.refresh()
        assertEquals(1, matviewCount(), "CONCURRENTLY refresh must surface the committed offer row")
        // recommended_score keeps its first term only (no yacht_extras count): (cabins + max_persons) / client_price
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM yacht_search_view WHERE recommended_score <> (4 + 8)::numeric / 1000",
                Int::class.java,
            ),
        )

        // Pool size is 1 -> this is the very connection the refresher used.
        assertEquals(defaultWorkMem, show("work_mem"))
        assertEquals(defaultLockTimeout, show("lock_timeout"))
        assertEquals(defaultJit, show("jit"))
    }

    @Test
    @Order(3)
    fun `re-running the repeatable on an existing matview swaps it in place with identical names and data`() {
        val before = indexNames()
        val rowsBefore = matviewCount()
        applyRepeatableLikeFlyway()
        assertEquals(before, indexNames())
        assertEquals(EXPECTED_INDEXES, indexNames())
        assertEquals(0, buildLeftovers())
        assertEquals(rowsBefore, matviewCount())
        // the swapped-in relation still refreshes concurrently (unique index renamed correctly)
        refresher.refresh()
        assertEquals(rowsBefore, matviewCount())
    }

    private fun seedOneExternalOffer() {
        jdbcTemplate.execute("INSERT INTO location (id, display_name, country_code) VALUES (1, 'Test marina | Split', 'HR')")
        jdbcTemplate.execute("INSERT INTO agency (id, name, active, availability_blocked) VALUES (1, 'Test agency', true, false)")
        jdbcTemplate.execute(
            "INSERT INTO yacht (id, name, agency_id, entry_type, sys_active, cabins, max_persons) VALUES (1, 'Test yacht', 1, 'EXTERNAL', true, 4, 8)",
        )
        jdbcTemplate.execute(
            """
            INSERT INTO offer (id, yacht_id, location_from, location_to, date_from, date_to, client_price, status)
            VALUES (1, 1, 1, 1, DATE '2030-06-01', DATE '2030-06-08', 1000, 'FREE')
            """.trimIndent(),
        )
    }
}
