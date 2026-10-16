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
 * V9_91 (yacht_image.hidden / hidden_reason / layout_override, owner incident 16.10.2026) on PostgreSQL 18 (prod
 * major), the file in one transaction like Flyway and like the hand pre-apply (psql -1).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YachtImageHiddenMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }

        private const val V9_91 = "V9_91__yacht_image_hidden_layout_override.sql"
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
            CREATE TABLE yacht_image (id bigserial PRIMARY KEY, url varchar(255), external_url varchar(255), "position" smallint,
                                      yacht_id bigint NOT NULL, main_image boolean, synced boolean DEFAULT false);
            INSERT INTO yacht_image (url, external_url, "position", yacht_id, main_image, synced)
            VALUES ('y-4345/a.webp', 'https://ws.nausys.com/CBMS-external/rest/yachtModel/4327922/pictures/main.jpg', 0, 4345, true, true),
                   ('y-4345/b.webp', 'https://ws.nausys.com/CBMS-external/rest/yacht/50898648/pictures/n2.jpg', 2, 4345, false, true);
            """.trimIndent(),
        )
    }

    private fun sql() = ClassPathResource("db/migration/$V9_91").inputStream.bufferedReader().readText()

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

    @Test
    fun `three columns, existing rows visible and decided by the file name, the partial index - a second run changes nothing`() {
        migrate()
        jdbc.queryForList(
            "SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' ' || coalesce(column_default, '-') " +
                "FROM information_schema.columns WHERE table_name = 'yacht_image' AND column_name IN ('hidden', 'hidden_reason', 'layout_override') " +
                "ORDER BY column_name",
            String::class.java,
        ) shouldContainExactly listOf("hidden boolean NO false", "hidden_reason text YES -", "layout_override boolean YES -")
        jdbc.queryForList("SELECT hidden::text || '/' || coalesce(layout_override::text, 'null') FROM yacht_image ORDER BY id", String::class.java) shouldContainExactly
            listOf("false/null", "false/null")
        jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'yacht_image_hidden_idx'", String::class.java)!! shouldContain
            "WHERE hidden"

        migrate() // the guard: everything present, nothing done
        jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'yacht_image'", Int::class.java) shouldBe 2
        // a new row (the partner sync's INSERT leaves the column out: Hibernate maps it read-only) gets the default
        jdbc.update("INSERT INTO yacht_image (url, yacht_id) VALUES ('y-1/c.webp', 1)")
        jdbc.queryForObject("SELECT count(*) FROM yacht_image WHERE hidden", Int::class.java) shouldBe 0
    }

    @Test
    fun `a sync holding yacht_image - the first apply waits and retries instead of failing, a re-run takes no lock at all`() {
        val sync = dataSource.connection
        sync.autoCommit = false
        sync.createStatement().use { it.execute("INSERT INTO yacht_image (yacht_id) VALUES (7)") } // RowExclusive until COMMIT
        val apply = CompletableFuture.runAsync { migrate() }
        Thread.sleep(1_000)
        apply.isDone shouldBe false // waiting for the lock (3 s per attempt), not failed
        sync.commit()
        sync.close()
        apply.get(30, TimeUnit.SECONDS)
        jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'yacht_image' AND column_name = 'hidden'", Int::class.java) shouldBe 1

        // pre-applied: the boot-time Flyway run must not queue behind a running sync
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("INSERT INTO yacht_image (yacht_id) VALUES (8)") }
            CompletableFuture.runAsync { migrate() }.get(10, TimeUnit.SECONDS)
            holder.rollback()
        }
    }
}
