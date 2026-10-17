package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * V9_92 (yacht_image.source, yacht.video_url / crew_profile_url; owner request 17.10.2026, gulet Allure 11769) on
 * PostgreSQL 18 (prod major), the file in one transaction like Flyway and like the hand pre-apply (psql -1).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtOwnMediaMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }

        private const val V9_92 = "V9_92__yacht_own_media.sql"
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
                maximumPoolSize = 3
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
        jdbc.execute(
            """
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255) NOT NULL, main_image_id bigint);
            CREATE TABLE yacht_image (id bigserial PRIMARY KEY, url varchar(255), external_url varchar(255), "position" smallint,
                                      yacht_id bigint NOT NULL REFERENCES yacht (id), main_image boolean, synced boolean DEFAULT false);
            INSERT INTO yacht (id, name, main_image_id) VALUES (11769, 'Allure', 200134);
            INSERT INTO yacht_image (id, url, external_url, "position", yacht_id, main_image, synced)
            VALUES (200134, 'y-11769/a.webp', 'https://www.booking-manager.com/cbm/documents/a.jpg', 0, 11769, true, true),
                   (200179, 'y-11769/b.webp', NULL, 0, 11769, false, true);
            """.trimIndent(),
        )
    }

    private fun sql() = ClassPathResource("db/migration/$V9_92").inputStream.bufferedReader().readText()

    /** The whole file on one connection in one transaction - what Flyway and psql -1 do. */
    private fun migrate() =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql()) }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    private fun columns(table: String) =
        jdbc.queryForList(
            "SELECT column_name || ' ' || data_type || coalesce('(' || character_maximum_length || ')', '') || ' ' || is_nullable || ' ' || " +
                "coalesce(column_default, '-') FROM information_schema.columns WHERE table_name = ? " +
                "AND column_name IN ('source', 'video_url', 'crew_profile_url') ORDER BY column_name",
            String::class.java,
            table,
        )

    @Test
    fun `three columns, every existing row PARTNER, the partial OWN index - a second run changes nothing`() {
        migrate()
        columns("yacht_image") shouldContainExactly listOf("source character varying(16) NO 'PARTNER'::character varying")
        columns("yacht") shouldContainExactly listOf("crew_profile_url character varying(255) YES -", "video_url character varying(512) YES -")
        // a custom-boat upload (no external_url) stays PARTNER too: no data is touched
        jdbc.queryForList("SELECT source FROM yacht_image ORDER BY id", String::class.java) shouldContainExactly listOf("PARTNER", "PARTNER")
        jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'yacht_image_own_idx'", String::class.java)!! shouldContain
            "WHERE ((source)::text = 'OWN'::text)"

        migrate() // the guard: everything present, nothing done
        jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'yacht_image'", Int::class.java) shouldBe 2
        // an old jar's sync INSERT leaves the column out: the default makes it a partner row
        jdbc.update("INSERT INTO yacht_image (url, yacht_id) VALUES ('y-11769/c.webp', 11769)")
        jdbc.queryForObject("SELECT count(*) FROM yacht_image WHERE source = 'PARTNER'", Int::class.java) shouldBe 3
        jdbc.update("INSERT INTO yacht_image (url, yacht_id, source) VALUES ('y-11769/own.webp', 11769, 'OWN')")
        jdbc.queryForObject("SELECT count(*) FROM yacht_image WHERE source = 'OWN'", Int::class.java) shouldBe 1
    }

    @Test
    fun `a sync holding yacht - the first apply waits and retries instead of failing, a re-run takes no lock at all`() {
        val sync = dataSource.connection
        sync.autoCommit = false
        sync.createStatement().use { it.execute("UPDATE yacht SET name = 'Allure' WHERE id = 11769") } // RowExclusive until COMMIT
        val apply = CompletableFuture.runAsync { migrate() }
        Thread.sleep(1_000)
        apply.isDone shouldBe false // waiting for the lock (3 s per attempt), not failed
        sync.commit()
        sync.close()
        apply.get(40, TimeUnit.SECONDS)
        columns("yacht").size shouldBe 2

        // pre-applied: the boot-time Flyway run must not queue behind a running sync
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use {
                it.execute("INSERT INTO yacht_image (yacht_id) VALUES (11769)")
                it.execute("UPDATE yacht SET name = 'Allure' WHERE id = 11769")
            }
            CompletableFuture.runAsync { migrate() }.get(10, TimeUnit.SECONDS)
            holder.rollback()
        }
    }
}
