package hr.workspace.boat4you.domains.catalouge.services

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldContainExactly
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

/**
 * 26.9.2026 audit B01: V9_68 pins the four Croatian regions whose name flipped with every partner sync, whatever
 * spelling the last sync left, keeps the other spelling as an alias, and location_view (R__1_07) exposes the aliases -
 * never a spelling that is another region's canonical name. Real migrations on PostgreSQL 18, run twice (idempotent).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegionCanonicalNamesMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate

    private fun run(file: String) {
        val sql = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $sql; COMMIT;")
    }

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
            CREATE TABLE country (id int PRIMARY KEY, name varchar(100), code2 varchar(2));
            CREATE TABLE region (id serial PRIMARY KEY, name varchar(100), country_id int, country_code varchar(2));
            CREATE TABLE location (id bigint PRIMARY KEY, name varchar(255), city varchar(100), country_code varchar(2),
                                   country_id int, lat numeric, lon numeric);
            CREATE TABLE yacht (id bigint PRIMARY KEY, location_id bigint);
            CREATE TABLE location_region (region_id int, location_id bigint);
            INSERT INTO country VALUES (54, 'Croatia', 'HR');
            -- the state a NauSys run leaves: every dual-mapped region under its NauSys spelling. r-2 "Kvarner" has no
            -- boats (not listed); r-8 "Split region" is a second, listed region under that spelling.
            INSERT INTO region (id, name, country_id, country_code) VALUES
                (2, 'Kvarner', 54, 'HR'),
                (3, 'Zadar region', 54, 'HR'),
                (4, 'Šibenik', 54, 'HR'),
                (5, 'Split region', 54, 'HR'),
                (6, 'Dubrovnik region', 54, 'HR'),
                (8, 'Split region', 54, 'HR'),
                (193, 'Kvarner', 54, 'HR');
            INSERT INTO location (id, name, city, country_code, country_id) VALUES (1, 'Marina A', NULL, 'HR', 54);
            INSERT INTO yacht VALUES (1, 1);
            INSERT INTO location_region VALUES (3, 1), (4, 1), (5, 1), (6, 1), (8, 1), (193, 1);
            """.trimIndent(),
        )
        repeat(2) {
            run("V9_68__region_canonical_names.sql")
            run("R__1_07_location_countries.sql")
        }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    private fun names() = jdbc.queryForList("SELECT id || ':' || name FROM region ORDER BY id", String::class.java)

    @Test
    fun `the flipping regions get their canonical name, every other name is left alone`() {
        names() shouldContainExactly
            listOf("2:Kvarner", "3:Zadar", "4:Šibenik", "5:Split", "6:Dubrovnik region", "8:Split region", "193:Istria / Kvarner")
    }

    @Test
    fun `aliases are exposed on the region rows, never the own name nor another listed region's canonical name`() {
        val aliases =
            jdbc
                .queryForList("SELECT id || '=' || COALESCE(aliases, '') FROM location_view WHERE location_type = 'REGION' ORDER BY real_id", String::class.java)
        // r-5's "Split region" is the listed r-8's name, so it keeps landing on r-8; r-2 "Kvarner" is not listed, so
        // "kvarner" resolves to r-193 again
        aliases shouldContainExactly listOf("r-3=Zadar region", "r-4=Šibenik region", "r-5=", "r-6=", "r-8=", "r-193=Kvarner")
        jdbc.queryForObject("SELECT search_filed FROM location_view WHERE id = 'r-3'", String::class.java) shouldBe
            "Zadar Croatia Zadar region"
    }
}
