package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTwinRepository
import hr.workspace.boat4you.domains.catalouge.utils.YachtNamePolicy
import hr.workspace.boat4you.domains.catalouge.utils.YachtNamePolicyFixtures
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.sql.Connection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * R02 (owner rule 14.10.2026) data migrations on PostgreSQL 18 (prod major), each file in one transaction like Flyway:
 * V9_84 (brand model labels -> builder model), V9_85 (yacht.partner_name, DDL only, V9_71 lock recipe), V9_86 (listing
 * twins keyed on the raw partner name), V9_87 (public yacht names + R31 lastmod). Yacht table with the production column
 * list and the real V9_71 lastmod trigger.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R02MigrationsTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val V9_84 = "V9_84__r02_brand_models_to_builder.sql"
        private const val V9_85 = "V9_85__r02_yacht_partner_name_column.sql"
        private const val V9_86 = "V9_86__r02_twin_key_partner_name.sql"
        private const val V9_87 = "V9_87__r02_yacht_public_name.sql"
        private val LONG_AGO: OffsetDateTime = OffsetDateTime.parse("2000-01-01T00:00:00Z")

        private val SCHEMA =
            """
            CREATE TABLE manufacturer (id bigint PRIMARY KEY, name varchar(255));
            CREATE TABLE model (id bigint PRIMARY KEY, name varchar(255) NOT NULL, manufacturer_id bigint);
            CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false, inquiry_only boolean NOT NULL DEFAULT false);
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255), country_code varchar(2));
            CREATE TABLE offer (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL, date_to date,
                                status text NOT NULL);
            CREATE TABLE external_mapping (id bigserial PRIMARY KEY, external_id bigint NOT NULL, system_id bigint NOT NULL,
                                           type varchar(100) NOT NULL, extended_type varchar(100), external_system_id integer NOT NULL,
                                           CONSTRAINT external_system_uk1 UNIQUE (external_id, external_system_id, system_id, type));
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

        private val SEED =
            """
            INSERT INTO manufacturer VALUES (1061, 'Leopard Catamarans'), (1080, 'Beneteau'), (561, 'Sunsail'), (7, 'Custom'),
                                            (8, 'Dufour');
            INSERT INTO model VALUES (4596, 'Sunsail 424', NULL), (2234, 'Leopard 42', 1061), (4593, 'Moorings 42.3', NULL),
                                     (2716, 'Oceanis 40.1', 1080), (4594, 'Moorings 352', NULL), (2701, 'Oceanis 35.1', 1080),
                                     (50, 'Gulet', 7), (51, 'Dufour 41', 8);
            INSERT INTO agency (id, name) VALUES (1, 'Sunsail Charter'), (2, 'Rhodes Yachting'), (3, 'Zenith'), (4, 'The Moorings');
            INSERT INTO location VALUES (1, 'Marina A', 'HR'), (2, 'Mandraki', 'GR');
            -- MMK (1) model mappings: 112 is also owned by the target already (a leftover), 115 is a NauSys mapping of the target
            INSERT INTO external_mapping (external_id, system_id, type, external_system_id) VALUES
                (111, 4596, 'Model', 1), (112, 4596, 'Model', 1), (112, 2234, 'Model', 1), (113, 4593, 'Model', 1),
                (114, 4594, 'Model', 1), (115, 2234, 'Model', 2);
            INSERT INTO yacht (id, name, agency_id, location_id, model_id, build_year, length, vessel_type, entry_type, sys_active) VALUES
                (1, 'Sunsail 424/4/4 Premium', 1, 1, 4596, 2019, 12.8, 'CATAMARAN', 'EXTERNAL', true),
                (2, 'Sunsail 424/4/4 Classic', 1, 1, 4596, 2016, 12.8, 'CATAMARAN', 'EXTERNAL', false),
                (3, 'Moorings 42.3 Club', 4, 1, 4593, 2018, 12.43, 'SAILING_YACHT', 'EXTERNAL', true),
                (4, 'Oceanis 35.1 Club', 4, 1, 4594, 2019, 10.4, 'SAILING_YACHT', 'EXTERNAL', true),
                (5, 'Gulet', 3, 1, 50, 2003, 24, 'GULET', 'EXTERNAL', true),
                (6, 'Rhodes Yachting', 2, 2, 2716, 2018, 12.43, 'SAILING_YACHT', 'EXTERNAL', true),
                (7, 'Zenith', 3, 2, 2716, 2018, 12.43, 'SAILING_YACHT', 'EXTERNAL', true),
                -- two DIFFERENT fleet boats of two agencies at one base: both read "Boat4You 41.3 Premium" after V9_87
                (10, 'Sunsail 41.3 Premium', 1, 1, 51, 2018, 12.35, 'SAILING_YACHT', 'EXTERNAL', true),
                (11, 'Moorings 41.3 Premium', 4, 1, 51, 2018, 12.35, 'SAILING_YACHT', 'EXTERNAL', true),
                -- one boat listed by two agencies: a twin pair before and after
                (12, 'Desafinado', 1, 1, 2234, 2017, 12.67, 'CATAMARAN', 'EXTERNAL', true),
                (13, 'DESAFINADO', 4, 1, 2234, 2017, 12.67, 'CATAMARAN', 'EXTERNAL', true);
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
        val from = LocalDate.now().plusWeeks(1)
        listOf(10L, 11L, 12L, 13L).forEach {
            jdbc.update(
                "INSERT INTO offer (yacht_id, date_from, date_to, status) VALUES (?, ?, ?, 'FREE')",
                it,
                java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(from.plusDays(7)),
            )
        }
        listOf("V9_69__yacht_listing_twin.sql", "V9_70__yacht_twin_manual_pair.sql", "V9_71__yacht_content_modified.sql").forEach { migrate(it) }
        stampAll(LONG_AGO)
    }

    private fun sql(file: String) = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()

    /** The whole file on one connection in one transaction - what Flyway does. */
    private fun migrate(
        file: String,
        conn: Connection? = null,
    ) {
        if (conn == null) {
            dataSource.connection.use { migrate(file, it) }
            return
        }
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute(sql(file)) }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Any lock wait on yacht fails the run instead of passing slowly. */
    private fun migrateFailingOnAnyLockWait(file: String) =
        dataSource.connection.use { flyway ->
            flyway.createStatement().use { it.execute("SET lock_timeout = '1s'") }
            try {
                migrate(file, flyway)
            } finally {
                flyway.createStatement().use { it.execute("RESET lock_timeout") }
            }
        }

    private fun stampAll(at: OffsetDateTime) {
        jdbc.update("INSERT INTO yacht_content_modified (yacht_id, modified_at) SELECT id, ? FROM yacht ON CONFLICT DO NOTHING", at)
        jdbc.update("UPDATE yacht_content_modified SET modified_at = ?", at)
    }

    private fun bumped(): List<Long> = jdbc.queryForList("SELECT yacht_id FROM yacht_content_modified WHERE modified_at > ? ORDER BY 1", Long::class.java, LONG_AGO)

    private fun models(): Map<Long, Long> = jdbc.query("SELECT id, model_id FROM yacht ORDER BY id") { rs, _ -> rs.getLong(1) to rs.getLong(2) }.toMap()

    private fun names(): Map<Long, String> = jdbc.query("SELECT id, name FROM yacht ORDER BY id") { rs, _ -> rs.getLong(1) to rs.getString(2) }.toMap()

    private fun modelMappings(): List<String> =
        jdbc.queryForList(
            "SELECT external_id || '@' || external_system_id || '->' || system_id FROM external_mapping WHERE type = 'Model' ORDER BY 1",
            String::class.java,
        )

    private fun twinPairs(): List<String> {
        jdbc.execute("REFRESH MATERIALIZED VIEW yacht_listing_twin")
        return jdbc.queryForList("SELECT yacht_id || '->' || canonical_yacht_id FROM yacht_listing_twin ORDER BY 1", String::class.java)
    }

    private fun count(sql: String): Long = jdbc.queryForObject(sql, Long::class.java)!!

    private fun allR02() = listOf(V9_84, V9_85, V9_86, V9_87).forEach { migrate(it) }

    // ---------------------------------------------------------------- V9_84

    @Test
    fun `V9_84 moves the boats of the certain brand labels to the builder model and repoints the mappings`() {
        migrate(V9_84)

        models() shouldBe mapOf(1L to 2234L, 2L to 2234L, 3L to 2716L, 4L to 2701L, 5L to 50L, 6L to 2716L, 7L to 2716L, 10L to 51L, 11L to 51L, 12L to 2234L, 13L to 2234L)
        // 112 already belonged to the target: the brand's copy is removed, not duplicated
        modelMappings() shouldContainExactly listOf("111@1->2234", "112@1->2234", "113@1->2716", "114@1->2701", "115@2->2234")
        count("SELECT count(*) FROM ops_model_relabel_backup_20261014") shouldBe 4L
        jdbc.queryForList("SELECT external_id || '->' || system_id FROM ops_model_mapping_backup_20261014 ORDER BY 1", String::class.java) shouldContainExactly
            listOf("111->4596", "112->4596", "113->4593", "114->4594")
        // the brand rows stay (empty); lastmod moved for the moved boats only (V9_71 trigger)
        count("SELECT count(*) FROM model WHERE id IN (4596, 4593, 4594)") shouldBe 3L
        bumped() shouldContainExactly listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `V9_84 a second run changes nothing and never overwrites a backup row`() {
        migrate(V9_84)
        val backup = jdbc.queryForList("SELECT yacht_id || ':' || old_model_id || ':' || backed_up_at FROM ops_model_relabel_backup_20261014 ORDER BY 1", String::class.java)
        stampAll(LONG_AGO)
        migrate(V9_84)
        jdbc.queryForList("SELECT yacht_id || ':' || old_model_id || ':' || backed_up_at FROM ops_model_relabel_backup_20261014 ORDER BY 1", String::class.java) shouldBe backup
        count("SELECT count(*) FROM ops_model_mapping_backup_20261014") shouldBe 4L
        bumped() shouldBe emptyList()
        // the backup tables carry no sequence default (no dependency on the live external_mapping_id_seq)
        count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'ops_model_mapping_backup_20261014' AND column_default IS NOT NULL") shouldBe 0L
    }

    @Test
    fun `V9_84 guards - a renamed source, a target with another maker and a NauSys-mapped source are skipped`() {
        jdbc.execute(
            """
            UPDATE model SET name = 'Moorings 42.3 Club' WHERE id = 4593;
            UPDATE model SET manufacturer_id = 561 WHERE id = 2701;
            INSERT INTO external_mapping (external_id, system_id, type, external_system_id) VALUES (999, 4596, 'Model', 2);
            """.trimIndent(),
        )
        val before = models()
        val mappingsBefore = modelMappings()
        migrate(V9_84)
        models() shouldBe before
        modelMappings() shouldBe mappingsBefore
        count("SELECT count(*) FROM ops_model_relabel_backup_20261014") shouldBe 0L
    }

    @Test
    fun `V9_84 accepts the accented Beneteau row as the target maker`() {
        jdbc.execute("UPDATE manufacturer SET name = 'Bénéteau' WHERE id = 1080")
        migrate(V9_84)
        models()[3] shouldBe 2716L
        models()[4] shouldBe 2701L
    }

    // ---------------------------------------------------------------- V9_85

    @Test
    fun `V9_85 adds the internal column once`() {
        migrate(V9_85)
        migrate(V9_85)
        count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'yacht' AND column_name = 'partner_name' AND data_type = 'text'") shouldBe 1L
        count("SELECT count(*) FROM yacht WHERE partner_name IS NOT NULL") shouldBe 0L
    }

    @Test
    fun `V9_85 pre-applied - the Flyway run takes no lock on yacht while a sync writes it`() {
        migrate(V9_85)
        dataSource.connection.use { sync ->
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 7 WHERE id = 1") }
            val started = System.nanoTime()
            migrateFailingOnAnyLockWait(V9_85)
            (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1)) shouldBe true
            sync.rollback()
            sync.autoCommit = true
        }
    }

    @Test
    fun `V9_85 not pre-applied - waits for a sync still writing yacht and retries instead of failing`() {
        dataSource.connection.use { sync ->
            // a sync holds yacht (ROW EXCLUSIVE) for 4.5 s - longer than one 3 s attempt
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 7 WHERE id = 1") }
            val started = System.nanoTime()
            val deploy = CompletableFuture.runAsync { migrate(V9_85) }
            Thread.sleep(4_500)
            deploy.isDone shouldBe false
            sync.commit()
            sync.autoCommit = true
            deploy.get(30, TimeUnit.SECONDS)
            (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(3)) shouldBe true
        }
        count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'yacht' AND column_name = 'partner_name'") shouldBe 1L
    }

    // ---------------------------------------------------------------- V9_86

    @Test
    fun `V9_86 keeps the twin pair set and the index name, and the public rename never pairs two fleet boats`() {
        val before = twinPairs()
        before shouldContainExactly listOf("13->12")
        migrate(V9_85)
        migrate(V9_86)
        twinPairs() shouldBe before
        count("SELECT count(*) FROM pg_indexes WHERE indexname = 'yacht_listing_twin_yacht_uidx'") shouldBe 1L

        migrate(V9_87)
        names()[10] shouldBe names()[11]
        twinPairs() shouldBe before
        // what the V9_70 key (the public name) would have done: hide one of two different boats
        migrate("V9_70__yacht_twin_manual_pair.sql")
        twinPairs() shouldContainExactly listOf("11->10", "13->12")
    }

    // ---------------------------------------------------------------- V9_87

    @Test
    fun `V9_87 writes only the rows whose public name differs, incl inactive ones, and keeps the raw name internal`() {
        allR02()
        names() shouldBe
            mapOf(
                1L to "Boat4You 424/4/4 Premium",
                2L to "Boat4You 424/4/4 Classic",
                3L to "Boat4You 42.3 Club",
                4L to "Oceanis 35.1 Club",
                5L to "Gulet",
                6L to "Boat4You",
                7L to "Zenith",
                10L to "Boat4You 41.3 Premium",
                11L to "Boat4You 41.3 Premium",
                12L to "Desafinado",
                13L to "DESAFINADO",
            )
        jdbc.queryForList("SELECT id || ':' || partner_name FROM yacht WHERE partner_name IS NOT NULL ORDER BY id", String::class.java) shouldContainExactly
            listOf(
                "1:Sunsail 424/4/4 Premium",
                "2:Sunsail 424/4/4 Classic",
                "3:Moorings 42.3 Club",
                "6:Rhodes Yachting",
                "10:Sunsail 41.3 Premium",
                "11:Moorings 41.3 Premium",
            )
        jdbc.queryForList("SELECT yacht_id || ':' || name FROM ops_yacht_name_backup_20261014 ORDER BY yacht_id", String::class.java) shouldContainExactly
            listOf(
                "1:Sunsail 424/4/4 Premium",
                "2:Sunsail 424/4/4 Classic",
                "3:Moorings 42.3 Club",
                "6:Rhodes Yachting",
                "10:Sunsail 41.3 Premium",
                "11:Moorings 41.3 Premium",
            )
        jdbc.queryForList("SELECT step || '=' || rows FROM _r02_v9_87_done ORDER BY step", String::class.java) shouldContainExactly
            listOf("names=6", "r31_lastmod=1")
    }

    @Test
    fun `V9_87 moves lastmod of the renamed boats (trigger) and of the R31 boats (explicit), a second run is a no-op`() {
        listOf(V9_84, V9_85, V9_86).forEach { migrate(it) }
        stampAll(LONG_AGO)
        migrate(V9_87)
        // renamed: 1 2 3 6 10 11 (trigger); "Gulet" / model "Gulet": 5 (the slug drops the name)
        bumped() shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 5L, 6L, 10L, 11L)

        val names = names()
        val stamps = jdbc.queryForList("SELECT yacht_id || '@' || modified_at FROM yacht_content_modified ORDER BY 1", String::class.java)
        migrate(V9_87)
        names() shouldBe names
        jdbc.queryForList("SELECT yacht_id || '@' || modified_at FROM yacht_content_modified ORDER BY 1", String::class.java) shouldBe stamps
        count("SELECT count(*) FROM ops_yacht_name_backup_20261014") shouldBe 6L
        count("SELECT count(*) FROM _r02_v9_87_done") shouldBe 2L
    }

    @Test
    fun `V9_87 waits for a sync holding a boat's row and still writes every name`() {
        listOf(V9_84, V9_85, V9_86).forEach { migrate(it) }
        dataSource.connection.use { sync ->
            // a sync transaction holds yacht 1's row for 5 s - longer than one 3 s attempt
            sync.autoCommit = false
            sync.createStatement().use { it.executeUpdate("UPDATE yacht SET cabins = 7 WHERE id = 1") }
            val started = System.nanoTime()
            val deploy = CompletableFuture.runAsync { migrate(V9_87) }
            Thread.sleep(5_000)
            deploy.isDone shouldBe false
            sync.commit()
            sync.autoCommit = true
            deploy.get(60, TimeUnit.SECONDS)
            (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(3)) shouldBe true
        }
        names()[1] shouldBe "Boat4You 424/4/4 Premium"
        count("SELECT count(*) FROM yacht WHERE partner_name IS NOT NULL") shouldBe 6L
    }

    @Test
    fun `R02NamePolicyParity - the SQL rule equals YachtNamePolicy on every fixture and on the curated list`() {
        allR02()
        (YachtNamePolicyFixtures.CASES.map { it.first } + YachtNamePolicy.brandNames).forEach { raw ->
            withClue(raw) {
                jdbc.queryForObject("SELECT public.r02_yacht_public_name_v1(?)", String::class.java, raw) shouldBe
                    YachtNamePolicy.publicName(raw)
            }
        }
        YachtNamePolicy.brandNames.forEach { withClue(it) { YachtNamePolicy.publicName(it) shouldBe "Boat4You" } }
    }

    @Test
    fun `the twin resolver matches on the raw name - two fleet boats with one public name are not one boat`() {
        allR02()
        val named = NamedParameterJdbcTemplate(jdbc)
        named.queryForList(YachtTwinRepository.FIND_TWIN_IDS_SQL, mapOf("id" to 10L), Long::class.java) shouldContainExactly listOf(10L)
        named.queryForList(YachtTwinRepository.FIND_TWIN_IDS_SQL, mapOf("id" to 12L), Long::class.java).sorted() shouldContainExactly listOf(12L, 13L)
    }
}
