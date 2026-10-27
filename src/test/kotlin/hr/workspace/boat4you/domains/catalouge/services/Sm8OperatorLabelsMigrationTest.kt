package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * V9_99 (SM8, 27.10.2026) on PostgreSQL 18, the file in one transaction like Flyway, over the production labels of the
 * boats it fixes and the ones it leaves. The new frozen slugs are checked against the Kotlin rule (SlugUtils on the labels the migration leaves),
 * which is the only slug rule there is.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Sm8OperatorLabelsMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val V9_99 = "V9_99__sm8_operator_names_out_of_boat_labels.sql"
        private val LONG_AGO: OffsetDateTime = OffsetDateTime.parse("2000-01-01T00:00:00Z")
        private val FIXED = listOf(867L, 868L, 869L, 870L)

        private val SCHEMA =
            """
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255) NOT NULL);
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
            CREATE TABLE external_mapping (id bigserial PRIMARY KEY, external_id bigint NOT NULL, system_id bigint NOT NULL,
                                           type varchar(100) NOT NULL, extended_type varchar(100), external_system_id integer NOT NULL,
                                           CONSTRAINT external_system_uk1 UNIQUE (external_id, external_system_id, system_id, type));
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, partner_name text, public_slug varchar(255),
                                model_id bigint NOT NULL, location_id bigint, build_year smallint, cabins smallint, berths smallint,
                                wc smallint, max_persons smallint, main_image_id bigint, vessel_type varchar(255) NOT NULL,
                                entry_type varchar(255) NOT NULL DEFAULT 'EXTERNAL', sys_active boolean NOT NULL DEFAULT true);
            CREATE TABLE yacht_content_modified (yacht_id bigint PRIMARY KEY, modified_at timestamptz NOT NULL);
            """.trimIndent()

        // Production rows 27.10.2026 (public API + sitemap), plus controls.
        private val SEED =
            """
            INSERT INTO manufacturer VALUES (158, 'Custom Made'), (308, 'Kolotura'), (419, 'Odisej Ltd'), (1080, 'Beneteau'),
                                            (330, 'Lagoon');
            INSERT INTO model VALUES (9004, 'M/S Ad Astra', 308), (9001, 'MS Custom Aurum Sky', 419), (9002, 'MY Custom Anthea', 419),
                                     (9003, 'MY Custom Black Swan', 419), (5542, 'Sunsail 41', 1080), (5543, 'Moorings 5000', 1080),
                                     (300, 'Lagoon 42', 330), (301, 'Oceanis 41', 1080);
            -- partner builder mappings: 7001 MMK and 7002 NauSys of the agencies; 7003 is mapped to both already
            INSERT INTO external_mapping (external_id, system_id, type, external_system_id) VALUES
                (7001, 419, 'Manufacturer', 1), (7002, 308, 'Manufacturer', 2), (7003, 419, 'Manufacturer', 1),
                (7003, 158, 'Manufacturer', 1), (7004, 1080, 'Manufacturer', 1), (8001, 5542, 'Model', 1);
            INSERT INTO yacht (id, name, partner_name, public_slug, model_id, vessel_type, sys_active) VALUES
                (867, 'M/S Ad Astra', 'M/S Ad Astra', 'kolotura-ms-ad-astra-867', 9004, 'MOTOR_YACHT', true),
                (868, 'M/S Aurum Sky', 'M/S Aurum Sky', 'odisej-ltd-ms-custom-aurum-sky-868', 9001, 'MOTOR_YACHT', true),
                (869, 'Anthea', 'Anthea', 'odisej-ltd-my-custom-anthea-869', 9002, 'MOTOR_YACHT', true),
                (870, 'Black Swan', 'Black Swan', 'odisej-ltd-my-custom-black-swan-870', 9003, 'MOTOR_YACHT', true),
                (4202, 'Sea novels', 'Sea novels', 'lagoon-42-sea-novels-4202', 300, 'CATAMARAN', true),
                (20128, 'C''s The Day', 'C''s The Day', 'beneteau-sunsail-41-cs-the-day-20128', 5542, 'SAILING_YACHT', true),
                (20131, 'Diamond Girl', 'Diamond Girl', 'beneteau-moorings-5000-diamond-girl-20131', 5543, 'SAILING_YACHT', true),
                (20133, 'Good Vibrations', 'Good Vibrations', 'beneteau-moorings-5000-good-vibrations-20133', 5543, 'SAILING_YACHT', true),
                -- controls: an owner-named boat, a boat V9_87 already renamed, a boat on a builder model; 4202 and 20128-20133 stay as they are
                (7, 'Zenith', 'Zenith', 'beneteau-oceanis-41-zenith-7', 301, 'SAILING_YACHT', true),
                (6, 'Boat4You', 'Rhodes Yachting', 'beneteau-oceanis-41-boat4you-6', 301, 'SAILING_YACHT', true);
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate

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
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun reset() {
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO PUBLIC")
        jdbc.execute(SCHEMA)
        jdbc.execute(SEED)
        jdbc.update("INSERT INTO yacht_content_modified (yacht_id, modified_at) SELECT id, ? FROM yacht", LONG_AGO)
        // V9_87's r02_yacht_public_name_v1 (v2 wraps it); its own data steps find nothing to rename in this seed
        jdbc.execute(sql("V9_87__r02_yacht_public_name.sql").substringBefore("DO $$"))
    }

    private fun sql(file: String) = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()

    /** The whole file on one connection in one transaction - what Flyway does (and the by-hand `psql -1`). */
    private fun migrate() =
        dataSource.connection.use { conn: Connection ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql(V9_99)) }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    private fun bumped(): List<Long> = jdbc.queryForList("SELECT yacht_id FROM yacht_content_modified WHERE modified_at > ? ORDER BY 1", Long::class.java, LONG_AGO)

    private fun slugs(): Map<Long, String> = jdbc.query("SELECT id, public_slug FROM yacht ORDER BY id") { rs, _ -> rs.getLong(1) to rs.getString(2) }.toMap()

    /** What SlugUtils gives each boat's labels as the migration leaves them. */
    private fun computedSlugs(): Map<Long, String> =
        jdbc
            .query(
                "SELECT y.id, mf.name, m.name, y.name FROM yacht y JOIN model m ON m.id = y.model_id " +
                    "LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id ORDER BY y.id",
            ) { rs, _ -> rs.getLong(1) to SlugUtils.toSlugWithId(rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(1)) }
            .toMap()

    @Test
    fun `the agency builders move to Custom Made with their partner mappings, the duplicate mapping goes`() {
        migrate()
        jdbc.queryForList("SELECT id || '>' || manufacturer_id FROM model WHERE id IN (9001, 9002, 9003, 9004) ORDER BY id", String::class.java) shouldContainExactly
            listOf("9001>158", "9002>158", "9003>158", "9004>158")
        jdbc.queryForList(
            "SELECT external_id || '@' || external_system_id || '>' || system_id FROM external_mapping WHERE type = 'Manufacturer' ORDER BY 1",
            String::class.java,
        ) shouldContainExactly listOf("7001@1>158", "7002@2>158", "7003@1>158", "7004@1>1080")
    }

    @Test
    fun `boat names and the other model labels stay as they are`() {
        migrate()
        jdbc.queryForList("SELECT name FROM model WHERE id IN (5542, 5543) ORDER BY id", String::class.java) shouldContainExactly
            listOf("Sunsail 41", "Moorings 5000")
        jdbc.queryForMap("SELECT name, partner_name FROM yacht WHERE id = 4202") shouldBe mapOf("name" to "Sea novels", "partner_name" to "Sea novels")
        jdbc.queryForList("SELECT name FROM yacht WHERE id IN (6, 7) ORDER BY id", String::class.java) shouldContainExactly listOf("Boat4You", "Zenith")
    }

    @Test
    fun `every new frozen slug is the one SlugUtils gives the new labels, with no agency left in it`() {
        val before = slugs()
        migrate()
        val after = slugs()
        val computed = computedSlugs()
        FIXED.forEach { id ->
            withClue(id) {
                after[id] shouldBe computed[id]
                after[id]!! shouldEndWith "-$id"
                listOf("odisej", "kolotura").forEach { after[id]!! shouldNotContain it }
            }
        }
        after - FIXED.toSet() shouldBe before - FIXED.toSet()
        after[868] shouldBe "custom-made-ms-custom-aurum-sky-868"
        after[4202] shouldBe "lagoon-42-sea-novels-4202"
        after[20131] shouldBe "beneteau-moorings-5000-diamond-girl-20131"
    }

    @Test
    fun `lastmod moves for the fixed boats only, a second run changes nothing`() {
        migrate()
        bumped() shouldContainExactlyInAnyOrder FIXED
        jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", LONG_AGO)
        val slugs = slugs()
        migrate()
        bumped() shouldBe emptyList()
        slugs() shouldBe slugs
        jdbc.queryForList("SELECT step || '=' || rows FROM _sm8_v9_99_done ORDER BY step", String::class.java) shouldContainExactly
            listOf("builders=4", "slugs=4")
    }

    @Test
    fun `a slug changed by hand meanwhile is left alone, a boat without a frozen slug gets the new one`() {
        jdbc.update("UPDATE yacht SET public_slug = 'chosen-by-hand-868' WHERE id = 868")
        jdbc.update("UPDATE yacht SET public_slug = NULL WHERE id = 869")
        migrate()
        slugs()[868] shouldBe "chosen-by-hand-868"
        slugs()[869] shouldBe "custom-made-my-custom-anthea-869"
    }

    @Test
    fun `the backup holds every old value for the rollback`() {
        migrate()
        jdbc.queryForList("SELECT kind || ':' || row_id || ':' || COALESCE(name, public_slug, '') || ':' || COALESCE(ref_id::text, '') FROM ops_sm8_backup_20261027 WHERE kind <> 'builder_mapping' ORDER BY 1", String::class.java) shouldContainExactly
            listOf(
                "model_builder:9001:MS Custom Aurum Sky:419",
                "model_builder:9002:MY Custom Anthea:419",
                "model_builder:9003:MY Custom Black Swan:419",
                "model_builder:9004:M/S Ad Astra:308",
                "yacht_slug:867:kolotura-ms-ad-astra-867:",
                "yacht_slug:868:odisej-ltd-ms-custom-aurum-sky-868:",
                "yacht_slug:869:odisej-ltd-my-custom-anthea-869:",
                "yacht_slug:870:odisej-ltd-my-custom-black-swan-870:",
            )
        jdbc.queryForObject("SELECT count(*) FROM ops_sm8_backup_20261027 WHERE kind = 'builder_mapping'", Long::class.java) shouldBe 3L
    }

    @Test
    fun `it waits for a sync holding a boat's row and still writes everything`() {
        dataSource.connection.use { sync ->
            // an agency sync holds 870's row for 5 s - longer than one 3 s attempt
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 4 WHERE id = 870") }
            val deploy = CompletableFuture.runAsync { migrate() }
            Thread.sleep(5_000)
            deploy.isDone shouldBe false
            sync.commit()
            sync.autoCommit = true
            deploy.get(60, TimeUnit.SECONDS)
        }
        slugs()[870] shouldBe "custom-made-my-custom-black-swan-870"
    }
}
