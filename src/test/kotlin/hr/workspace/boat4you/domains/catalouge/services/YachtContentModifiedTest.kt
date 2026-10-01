package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Codex audit N7 (1.10.2026): the sitemaps' `<lastmod>` comes from yacht_content_modified, written by the V9_71 trigger
 * on yacht. Only a change of a field the public boat page renders moves it - never a sync re-saving unchanged values,
 * never a private field - and it never moves backwards. CREATE TRIGGER must wait for a sync still writing yacht instead
 * of failing the API start, and must never block readers meanwhile. The file is idempotent: applied by hand before the
 * deploy, the Flyway run at the API start takes no lock on yacht (review 1.10.2026: Flyway runs before the API serves,
 * so a lock wait there is downtime). Real V9_71 on PostgreSQL 18 (prod major), yacht with the production column list.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtContentModifiedTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val LONG_AGO: Instant = Instant.parse("2000-01-01T00:00:00Z")

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
            """.trimIndent()

        private const val BOAT =
            "INSERT INTO yacht (id, name, agency_id, location_id, model_id, deposit, build_year, length, cabins, wc, berths, " +
                "max_persons, mainsail_type, registration_number, option_approval, commision_perc, vessel_type, entry_type, " +
                "main_image_id, deposit_currency) VALUES (?, 'Pampero', 1, 10, 100, 2500, 2018, 11.55, 4, 2, 8, 8, 'CLASSIC', " +
                "'ST-1234', false, 20, 'CATAMARAN', 'EXTERNAL', 555, 'EUR')"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private val migration: String =
        ClassPathResource("db/migration/V9_71__yacht_content_modified.sql").inputStream.bufferedReader().readText()

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

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE yacht, yacht_content_modified")
        jdbc.update(BOAT, 1)
        // as if the boat had last changed long ago
        jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", OffsetDateTime.parse("2000-01-01T00:00:00Z"))
    }

    /** The whole V9_71 file on one connection in one transaction - what Flyway does. */
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

    private fun stamp(id: Long): Instant? =
        jdbc
            .query("SELECT modified_at FROM yacht_content_modified WHERE yacht_id = ?", RowMapper { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }, id)
            .firstOrNull()
            ?.toInstant()

    @Test
    fun `a new boat is recorded when it is inserted`() {
        val before = Instant.now().minusSeconds(5)
        jdbc.update(BOAT, 2)
        val recorded = stamp(2)
        recorded shouldNotBe null
        (recorded!! > before) shouldBe true
    }

    @Test
    fun `every field the boat page renders moves the time forward`() {
        val pageFields =
            listOf(
                "name = 'Pampero II'",
                "model_id = 101",
                "location_id = 11",
                "build_year = 2019",
                "length = 11.6",
                "beam = 6.5",
                "cabins = 5",
                "wc = 4",
                "berths = 10",
                "max_persons = 10",
                "engine_power = 80",
                "fuel_tank = 400",
                "water_tank = 600",
                "mainsail_type = 'FULL_BATTEN'",
                "deposit = 3000",
                "insured_deposit = 500",
                "deposit_currency = 'USD'",
                "crew_number = 1",
                "default_checkin = '17:00'",
                "default_checkout = '09:00'",
                "vessel_type = 'SAILING_YACHT'",
                "entry_type = 'CUSTOM'",
                "sys_active = false",
                "main_image_id = 556",
                "option_approval = true",
                "main_image_id = NULL",
            )
        pageFields.forEach { change ->
            jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", OffsetDateTime.parse("2000-01-01T00:00:00Z"))
            jdbc.update("UPDATE yacht SET $change WHERE id = 1")
            withClue(change) { (stamp(1)!! > LONG_AGO) shouldBe true }
        }
    }

    @Test
    fun `a sync re-saving unchanged values or changing a private field is no change`() {
        // Hibernate's UPDATE rewrites every column, changed or not
        jdbc.update(
            "UPDATE yacht SET name = name, model_id = model_id, location_id = location_id, build_year = build_year, " +
                "cabins = cabins, berths = berths, max_persons = max_persons, main_image_id = main_image_id, " +
                "sys_active = sys_active, beam = beam, option_approval = option_approval WHERE id = 1",
        )
        // what the public pages never show: commission, registration, agency, discounts, partner text fields
        jdbc.update(
            "UPDATE yacht SET commision_perc = 22, registration_number = 'ST-9999', agency_id = 2, exclude_discount = true, " +
                "max_discount = 10, charter_type = 'bareboat,crewed', draught = 1.2, launch_year = 2017, " +
                "option_to_reservation = true WHERE id = 1",
        )
        stamp(1) shouldBe LONG_AGO
    }

    @Test
    fun `the time never moves backwards`() {
        val future = OffsetDateTime.parse("2100-01-01T00:00:00Z")
        jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", future)
        jdbc.update("UPDATE yacht SET cabins = 6 WHERE id = 1")
        stamp(1) shouldBe future.toInstant()
    }

    @Test
    fun `creating the trigger waits for a sync still writing yacht, and readers never wait`() {
        jdbc.execute("DROP TRIGGER yacht_content_modified_insert ON yacht; DROP TRIGGER yacht_content_modified_update ON yacht")
        dataSource.connection.use { sync ->
            // a sync transaction holds yacht (ROW EXCLUSIVE) for 4.5 s - longer than one 3 s attempt
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET registration_number = 'ST-0001' WHERE id = 1") }
            val started = System.nanoTime()
            val deploy = CompletableFuture.runAsync { dataSource.connection.use { migrateLikeFlyway(it) } }
            Thread.sleep(1_500)
            deploy.isDone shouldBe false
            // the API keeps reading yacht while CREATE TRIGGER waits for its lock
            dataSource.connection.use { reader ->
                reader.createStatement().use { st ->
                    st.execute("SET statement_timeout = '1s'")
                    st.executeQuery("SELECT count(*) FROM yacht").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 1L
                    }
                }
            }
            Thread.sleep(3_000)
            sync.commit()
            sync.autoCommit = true
            deploy.get(30, TimeUnit.SECONDS)
            (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(3)) shouldBe true
        }
        jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'yacht_content_modified_%'", Long::class.java) shouldBe 2L
        jdbc.update("UPDATE yacht SET cabins = 6 WHERE id = 1")
        (stamp(1)!! > LONG_AGO) shouldBe true
    }

    @Test
    fun `applied by hand before the deploy, the Flyway run takes no lock on yacht while a sync writes it`() {
        // the triggers exist (setUp applied V9_71, as the manual step before the restart does)
        dataSource.connection.use { sync ->
            // a sync transaction is writing yacht (ROW EXCLUSIVE on yacht and on yacht_content_modified)
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
        jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'yacht_content_modified_%'", Long::class.java) shouldBe 2L
    }

    @Test
    fun `a half-applied state is completed - the missing trigger is created, the existing one replaced`() {
        jdbc.execute("DROP TRIGGER yacht_content_modified_update ON yacht")
        dataSource.connection.use { migrateLikeFlyway(it) }
        jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'yacht_content_modified_%'", Long::class.java) shouldBe 2L
        jdbc.update("UPDATE yacht SET cabins = 6 WHERE id = 1")
        (stamp(1)!! > LONG_AGO) shouldBe true
        jdbc.update(BOAT, 3)
        stamp(3) shouldNotBe null
    }
}
