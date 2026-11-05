package hr.workspace.boat4you.domains.pr

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * V9_121 on a real PostgreSQL (18, the prod major, through Testcontainers; or the database in PR_TEST_JDBC_URL when set,
 * for machines without Docker): the seven PR tables apply cleanly with the app's GRANTs, and the uniqueness rules the
 * services rely on hold in the database itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrSchemaTest {
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        // PR_TEST_JDBC_URL must name an EMPTY database (the migration creates its tables there).
        val ds =
            System.getenv("PR_TEST_JDBC_URL")?.let { url ->
                DriverManagerDataSource(url, System.getenv("PR_TEST_JDBC_USER") ?: "postgres", System.getenv("PR_TEST_JDBC_PASSWORD") ?: "")
            } ?: postgres.let { DriverManagerDataSource(it.jdbcUrl, it.username, it.password) }
        jdbc = JdbcTemplate(ds)
        // The whole file as Flyway runs it (one script); the GRANTs need the boat4you_app role, as on prod.
        jdbc.execute(ClassPathResource("db/migration/V9_121__pr_module.sql").inputStream.readAllBytes().decodeToString())
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE pr_mention, pr_alert_feed, pr_placement, pr_pitch, pr_study, pr_contact, pr_outlet RESTART IDENTITY CASCADE")
    }

    private fun fails(sql: String) = runCatching { jdbc.update(sql) }.isFailure

    @Test
    fun migrationCreatesAllPrTablesWithGrants() {
        val tables = listOf("pr_outlet", "pr_contact", "pr_study", "pr_pitch", "pr_placement", "pr_alert_feed", "pr_mention")
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE 'pr\\_%'",
            String::class.java,
        ) shouldContainExactlyInAnyOrder tables
        tables.forEach { t ->
            listOf("SELECT", "INSERT", "UPDATE", "DELETE").forEach { p ->
                jdbc.queryForObject("SELECT has_table_privilege('boat4you_app', '$t', '$p')", Boolean::class.java) shouldBe true
            }
            jdbc.queryForObject("SELECT has_sequence_privilege('boat4you_app', '${t}_id_seq', 'USAGE')", Boolean::class.java) shouldBe true
        }
    }

    @Test
    fun `one contact per address and outlet, compared case-insensitively, also without an outlet`() {
        jdbc.update("INSERT INTO pr_outlet (name, market, tier) VALUES ('The Times', 'GB', 1)")
        jdbc.update("INSERT INTO pr_contact (outlet_id, email) VALUES (1, 'jane@times.co.uk')")
        fails("INSERT INTO pr_contact (outlet_id, email) VALUES (1, 'Jane@Times.co.uk')") shouldBe true
        jdbc.update("INSERT INTO pr_contact (email) VALUES ('jane@times.co.uk')")
        fails("INSERT INTO pr_contact (email) VALUES ('JANE@times.co.uk')") shouldBe true
        // The same journalist writing for a second outlet is a second contact.
        jdbc.update("INSERT INTO pr_outlet (name, market, tier) VALUES ('Telegraph', 'GB', 1)")
        jdbc.update("INSERT INTO pr_contact (outlet_id, email) VALUES (2, 'jane@times.co.uk')")
        jdbc.queryForObject("SELECT status FROM pr_contact WHERE id = 1", String::class.java) shouldBe "ACTIVE"
    }

    @Test
    fun `outlet names are unique per market regardless of case, tier is 1 to 3`() {
        jdbc.update("INSERT INTO pr_outlet (name, market, tier) VALUES ('The Times', 'GB', 1)")
        fails("INSERT INTO pr_outlet (name, market, tier) VALUES ('THE TIMES', 'GB', 2)") shouldBe true
        jdbc.update("INSERT INTO pr_outlet (name, market, tier) VALUES ('The Times', 'US', 2)")
        fails("INSERT INTO pr_outlet (name, market, tier) VALUES ('Zero', 'GB', 0)") shouldBe true
        fails("INSERT INTO pr_outlet (name, market, tier) VALUES ('Four', 'GB', 4)") shouldBe true
    }

    @Test
    fun `one main pitch per contact and study, at most one follow-up per pitch`() {
        jdbc.update("INSERT INTO pr_contact (email) VALUES ('jane@times.co.uk')")
        jdbc.update("INSERT INTO pr_study (number, site_id, title) VALUES (1, 'boat4you', 'Study one')")
        jdbc.update("INSERT INTO pr_pitch (study_id, contact_id, template, subject, body) VALUES (1, 1, 'DATA_PITCH', 's', 'b')")
        fails("INSERT INTO pr_pitch (study_id, contact_id, template, subject, body) VALUES (1, 1, 'DATA_PITCH', 's2', 'b2')") shouldBe true
        jdbc.update("INSERT INTO pr_pitch (study_id, contact_id, parent_pitch_id, template, subject, body) VALUES (1, 1, 1, 'FOLLOW_UP', 'f', 'b')")
        fails("INSERT INTO pr_pitch (study_id, contact_id, parent_pitch_id, template, subject, body) VALUES (1, 1, 1, 'FOLLOW_UP', 'f2', 'b')") shouldBe true
        jdbc.queryForObject("SELECT status FROM pr_pitch WHERE id = 1", String::class.java) shouldBe "DRAFT"
        // Deleting the contact (24-month retention) takes its pitches along.
        jdbc.update("DELETE FROM pr_contact WHERE id = 1")
        jdbc.queryForObject("SELECT count(*) FROM pr_pitch", Long::class.java) shouldBe 0L
    }

    companion object {
        val postgres: PostgreSQLContainer<Nothing> by lazy {
            PostgreSQLContainer<Nothing>("postgres:18-alpine")
                .apply {
                    withDatabaseName("boat4you_db")
                    withInitScript("init/00_roles.sql")
                }.also { it.start() }
        }
    }
}
