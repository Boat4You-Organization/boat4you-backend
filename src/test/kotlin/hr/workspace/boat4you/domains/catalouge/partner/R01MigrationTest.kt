package hr.workspace.boat4you.domains.catalouge.partner

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V9_83 (R01 yacht_extras descriptions) through the real Flyway on PostgreSQL 18 (prod major): the SQL subset equals
 * PartnerCopySanitizer.description on every row it is allowed to touch, rows that need Kotlin-only rules (premises,
 * disclaimers, "the Company", contacts, HTML) and CUSTOM yachts are left alone, the backup holds each changed row once
 * with its first original, and a second run (Flyway out of order on cusma2, or the by-hand pre-apply then the deploy)
 * changes nothing because of the marker.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R01MigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        const val MIGRATION = "db/migration/V9_83__r01_partner_extras_descriptions.sql"

        /** The columns V9_83 reads, typed as on prod. */
        private val SCHEMA =
            """
            CREATE TABLE yacht (id bigint PRIMARY KEY, name varchar(255), entry_type varchar(255) NOT NULL);
            CREATE TABLE yacht_extras (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL REFERENCES yacht (id),
                                       name varchar, description text);
            INSERT INTO yacht (id, name, entry_type) VALUES (1, 'Partner boat', 'EXTERNAL'), (2, 'Our boat', 'CUSTOM');
            """.trimIndent()

        /** Rows the SQL subset rewrites: their result must equal the Kotlin sanitizer byte for byte. */
        val CLEANED =
            listOf(
                "Navbow Seascooter (+ deposit 600€ - to be confirmed by NSS)",
                "Early Check-in (14:00pm - to be reconfirmed by NSS office)",
                "Embarkation/Disembarkation Martinique (to be reconfirmed by NSS office)",
                "Half board 1-3 pax (number of guests to update manually)",
                "1 set of towels per person - to be updated manually",
                "Navigare Carefree surcharge for two/more weeks",
                "Moorings 4200/3/3 Club - The Moorings fleet",
                "Kiriacoulis / Istion",
                "Drinks package by NSS Charter (NSS)",
                "Moorings's welcome pack",
                "Skipper  for   the week (to update manually)",
                "Sunsail 424 delivered by Sunsail\nreturn to Sunsail",
                "Rhodes Yachting and Active Cruises and Kavas Yachting",
                "Opening hours of the NSS office: 8-20h",
            )

        /** Rows the SQL leaves to the sync (Kotlin-only rules), although they name a partner. */
        val SKIPPED =
            listOf(
                "Mooring fees (first and last day at the Athenian Yachts' Pier)",
                "First night at NSS pontoon",
                "First & last night at the Dream Yacht Charter pontoon",
                "Parking at Athenian Yachts base",
                "Luggage storage in The Moorings office",
                "Athenian Yachts solely provides facilitation services.",
                "Hermes Yachting shall not be liable for the crew.",
                "The Company (NSS) collects the deposit.",
                "Contact NSS: info@nss.example",
                "Call NSS for the transfer",
                "NSS transfer +30 210 1234567",
                "<b>NSS</b> transfer",
                "Menu www.navigare.example (Navigare)",
            )

        /** Rows no rule touches. */
        val UNTOUCHED =
            listOf(
                "BVI Cruising Tax",
                "mooring fees, paid at the marina",
                "Gas (Sun Odyssey 349) - Hermes",
                "nss in lower case is not the partner",
                "Moorings fleet without a model number",
            )
    }

    private val sources: Path = Files.createTempDirectory("r01-migration")
    private val dataSources = mutableListOf<HikariDataSource>()
    private val sql: String = ClassPathResource(MIGRATION).inputStream.bufferedReader().readText()

    @BeforeAll
    fun copyMigration() {
        Files.writeString(sources.resolve(MIGRATION.substringAfterLast('/')), sql)
    }

    @AfterAll
    fun tearDown() {
        dataSources.forEach { it.close() }
    }

    private fun database(name: String): JdbcTemplate {
        JdbcTemplate(dataSource(postgres.jdbcUrl)).execute("CREATE DATABASE $name")
        val jdbc = JdbcTemplate(dataSource(postgres.jdbcUrl.replace("/boat4you_db", "/$name")))
        jdbc.execute(SCHEMA)
        return jdbc
    }

    private fun dataSource(url: String) =
        HikariDataSource()
            .apply {
                jdbcUrl = url
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }.also { dataSources += it }

    private fun migrate(jdbc: JdbcTemplate) =
        Flyway
            .configure()
            .dataSource(jdbc.dataSource)
            .locations("filesystem:$sources")
            .baselineOnMigrate(true)
            .baselineVersion("9.82")
            .load()
            .migrate()

    private fun insert(
        jdbc: JdbcTemplate,
        yachtId: Long,
        texts: List<String>,
    ): Map<Long, String> =
        texts.associate { text ->
            jdbc.queryForObject(
                "INSERT INTO yacht_extras (yacht_id, name, description) VALUES (?, 'Extra', ?) RETURNING id",
                Long::class.java,
                yachtId,
                text,
            )!! to text
        }

    private fun descriptions(jdbc: JdbcTemplate): Map<Long, String?> =
        jdbc.queryForList("SELECT id, description FROM yacht_extras").associate { (it["id"] as Number).toLong() to it["description"] as String? }

    @Test
    fun `cleans the SQL subset like Kotlin, skips Kotlin-only rows and CUSTOM yachts, backs up once, re-runs as a no-op`() {
        val jdbc = database("r01_main")
        val cleaned = insert(jdbc, 1, CLEANED)
        val skipped = insert(jdbc, 1, SKIPPED)
        val untouched = insert(jdbc, 1, UNTOUCHED)
        val custom = insert(jdbc, 2, CLEANED.take(3))

        assertEquals(1, migrate(jdbc).migrationsExecuted)

        val after = descriptions(jdbc)
        cleaned.forEach { (id, raw) ->
            val expected = PartnerCopySanitizer.description(raw)
            assertTrue(expected != raw, "fixture must change: $raw")
            assertEquals(expected, after[id], "SQL == Kotlin for: $raw")
        }
        (skipped + untouched + custom).forEach { (id, raw) -> assertEquals(raw, after[id], "untouched: $raw") }

        val backup =
            jdbc.queryForList("SELECT id, description FROM _r01_yacht_extras_backup").associate {
                (it["id"] as Number).toLong() to it["description"] as String?
            }
        assertEquals(cleaned, backup, "every changed row backed up once with its original text, nothing else")
        assertEquals(CLEANED.size, jdbc.queryForObject("SELECT rows FROM _r01_v9_83_done WHERE step = 'yacht_extras'", Int::class.java))

        // A second run (the pre-applied file again under Flyway on another node, or by hand): the marker stops it, even
        // when a sync has meanwhile written partner text back and an original changed.
        jdbc.update("UPDATE yacht_extras SET description = 'Fuel - to be confirmed by NSS' WHERE id = ?", cleaned.keys.first())
        jdbc.execute("BEGIN; $sql; COMMIT;")
        assertEquals("Fuel - to be confirmed by NSS", descriptions(jdbc)[cleaned.keys.first()])
        assertEquals(cleaned, jdbc.queryForList("SELECT id, description FROM _r01_yacht_extras_backup").associate {
            (it["id"] as Number).toLong() to it["description"] as String?
        })
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM _r01_v9_83_done", Int::class.java))
    }

    @Test
    fun `the SQL function equals the Kotlin description on every fixture it is allowed to touch`() {
        val jdbc = database("r01_function")
        migrate(jdbc)
        (CLEANED + UNTOUCHED).forEach { raw ->
            val sqlResult = jdbc.queryForObject("SELECT public.r01_partner_copy_v1(?)", String::class.java, raw)
            assertEquals(PartnerCopySanitizer.description(raw), sqlResult, raw)
        }
        assertEquals(null, jdbc.queryForObject("SELECT public.r01_partner_copy_v1('(to update manually)')", String::class.java))
    }

    @Test
    fun `a fresh database runs it as a no-op that still sets the marker`() {
        val jdbc = database("r01_fresh")
        assertEquals(1, migrate(jdbc).migrationsExecuted)
        assertEquals(0, jdbc.queryForObject("SELECT rows FROM _r01_v9_83_done WHERE step = 'yacht_extras'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM _r01_yacht_extras_backup", Int::class.java))
    }
}
