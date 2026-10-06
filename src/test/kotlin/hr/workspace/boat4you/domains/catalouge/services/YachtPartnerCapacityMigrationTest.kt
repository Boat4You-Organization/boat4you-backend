package hr.workspace.boat4you.domains.catalouge.services

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
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * V9_72 (capacity contract v1, 6.10.2026) adds 14 nullable partner columns to yacht. ADD COLUMN needs ACCESS EXCLUSIVE
 * on yacht even when the columns exist (critique B-3), so the file first checks information_schema and returns without
 * any lock when all 14 are there (applied by hand before the restart), otherwise retries the ALTER with a 1 s lock
 * timeout while a sync writes yacht - while an attempt waits, reads of yacht queue behind it too, so each wait is short.
 * Real V9_72 on PostgreSQL 18 (prod major), yacht with the production column list.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtPartnerCapacityMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        /** yacht as on production (information_schema, 1.10.2026). */
        private val SCHEMA =
            """
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, agency_id bigint, location_id bigint,
                                model_id bigint NOT NULL, deposit numeric, insured_deposit numeric, build_year smallint,
                                launch_year smallint, engine_power smallint, length numeric, draught numeric, beam numeric,
                                water_tank integer, fuel_tank integer, cabins smallint, crew_cabins smallint, wc smallint,
                                crew_wc smallint, berths smallint, crew_berths smallint, max_persons smallint,
                                default_checkin varchar(10), default_checkout varchar(10), mainsail_type varchar(255),
                                mainsail_area numeric, genoa_type varchar(50), genoa_area numeric,
                                registration_number varchar(50), option_approval boolean, option_to_reservation boolean,
                                commision numeric, commision_perc numeric, exclude_discount boolean, max_discount numeric,
                                agency_discount_type varchar(50), max_discount_from_commision numeric,
                                charter_type varchar(250), crewed_type varchar(20), vessel_type varchar(255) NOT NULL,
                                entry_type varchar(255) NOT NULL, sys_active boolean NOT NULL DEFAULT true,
                                main_image_id bigint, deposit_currency varchar(20), crew_number smallint);
            INSERT INTO yacht (id, name, model_id, cabins, berths, wc, max_persons, vessel_type, entry_type)
            VALUES (1, 'Dione II', 100, 6, 13, 6, 14, 'SAILING_YACHT', 'EXTERNAL');
            """.trimIndent()

        private val NEW_COLUMNS: Map<String, String> =
            linkedMapOf(
                "cabins_note" to "text",
                "berths_note" to "text",
                "wc_note" to "text",
                "cabin_berths" to "smallint",
                "salon_berths" to "smallint",
                "showers" to "smallint",
                "crew_showers" to "smallint",
                "recommended_persons" to "smallint",
                "mainsail_label" to "text",
                "genoa_label" to "text",
                "engine_label" to "text",
                "engine_count" to "smallint",
                "engine_power_each" to "numeric",
                "internal_remark" to "text",
            )
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private val migration: String =
        ClassPathResource("db/migration/V9_72__yacht_partner_capacity.sql").inputStream.bufferedReader().readText()

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(SCHEMA)
        dataSource.connection.use { migrateLikeFlyway(it) }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    /** The whole V9_72 file on one connection in one transaction - what Flyway does. */
    private fun migrateLikeFlyway(conn: Connection) {
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute(migration) }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun newColumns(): Map<String, String> =
        jdbc
            .queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'yacht' AND column_name IN (${NEW_COLUMNS.keys.joinToString { "'$it'" }})",
            ).associate { it["column_name"] as String to it["data_type"] as String }

    @Test
    fun `adds the 14 nullable partner columns, documented, and leaves the existing row as it was`() {
        newColumns() shouldBe NEW_COLUMNS
        jdbc.queryForObject(
            "SELECT numeric_precision * 100 + numeric_scale FROM information_schema.columns " +
                "WHERE table_name = 'yacht' AND column_name = 'engine_power_each'",
            Int::class.java,
        ) shouldBe 702
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'yacht' AND column_name IN " +
                "(${NEW_COLUMNS.keys.joinToString { "'$it'" }}) AND (is_nullable <> 'YES' OR column_default IS NOT NULL)",
            Long::class.java,
        ) shouldBe 0L
        val remarkComment =
            jdbc.queryForObject(
                "SELECT col_description('public.yacht'::regclass, attnum) FROM pg_attribute " +
                    "WHERE attrelid = 'public.yacht'::regclass AND attname = 'internal_remark'",
                String::class.java,
            )
        remarkComment?.startsWith("ADMIN ONLY.") shouldBe true
        val existing = jdbc.queryForObject("SELECT cabins || '/' || berths || '/' || wc || '/' || max_persons FROM yacht WHERE id = 1", String::class.java)
        existing shouldBe "6/13/6/14"
        jdbc.queryForObject(
            "SELECT count(*) FROM yacht WHERE id = 1 AND cabins_note IS NULL AND engine_power_each IS NULL AND internal_remark IS NULL",
            Long::class.java,
        ) shouldBe 1L
    }

    @Test
    fun `applied by hand before the deploy, the Flyway run takes no lock on yacht while a sync writes it`() {
        dataSource.connection.use { sync ->
            // a sync transaction is writing yacht (ROW EXCLUSIVE)
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 7 WHERE id = 1") }
            val started = System.nanoTime()
            dataSource.connection.use { flyway ->
                // any lock wait on yacht would fail the run instead of passing slowly
                flyway.createStatement().use { it.execute("SET lock_timeout = '1s'") }
                try {
                    migrateLikeFlyway(flyway)
                } finally {
                    flyway.createStatement().use { it.execute("RESET lock_timeout") }
                }
            }
            (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1)) shouldBe true
            sync.rollback()
            sync.autoCommit = true
        }
        newColumns() shouldBe NEW_COLUMNS
    }

    @Test
    fun `a half-applied state waits for a sync still writing yacht, then completes`() {
        jdbc.execute("ALTER TABLE yacht DROP COLUMN internal_remark, DROP COLUMN engine_power_each")
        dataSource.connection.use { sync ->
            // a sync transaction holds yacht (ROW EXCLUSIVE) for 4.5 s - longer than two 1 s attempts
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 6 WHERE id = 1") }
            val started = System.nanoTime()
            val deploy = CompletableFuture.runAsync { dataSource.connection.use { migrateLikeFlyway(it) } }
            Thread.sleep(4_500)
            deploy.isDone shouldBe false
            sync.commit()
            sync.autoCommit = true
            deploy.get(30, TimeUnit.SECONDS)
            (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(3)) shouldBe true
        }
        newColumns() shouldBe NEW_COLUMNS
    }

    @Test
    fun `while it waits for a sync, a read of yacht waits at most one 1 s attempt`() {
        jdbc.execute("ALTER TABLE yacht DROP COLUMN internal_remark")
        dataSource.connection.use { sync ->
            // a sync transaction holds yacht (ROW EXCLUSIVE) while the migration retries
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 6 WHERE id = 1") }
            val deploy = CompletableFuture.runAsync { dataSource.connection.use { migrateLikeFlyway(it) } }
            Thread.sleep(300)
            // a boat page reading yacht queues behind the waiting ALTER, but only until that attempt's lock_timeout
            val reads =
                (1..4).map {
                    val started = System.nanoTime()
                    jdbc.queryForObject("SELECT name FROM yacht WHERE id = 1", String::class.java) shouldBe "Dione II"
                    Thread.sleep(250)
                    System.nanoTime() - started - TimeUnit.MILLISECONDS.toNanos(250)
                }
            (reads.max() > TimeUnit.MILLISECONDS.toNanos(200)) shouldBe true // the first read did queue behind the ALTER
            (reads.max() < TimeUnit.MILLISECONDS.toNanos(1_600)) shouldBe true // ...for one 1 s attempt at most (3 s before)
            deploy.isDone shouldBe false
            sync.commit()
            sync.autoCommit = true
            deploy.get(30, TimeUnit.SECONDS)
        }
        newColumns() shouldBe NEW_COLUMNS
    }
}
