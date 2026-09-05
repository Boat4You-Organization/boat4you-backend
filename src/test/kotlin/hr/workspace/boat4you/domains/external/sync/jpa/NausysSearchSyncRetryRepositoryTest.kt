package hr.workspace.boat4you.domains.external.sync.jpa

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real V9_55 script against PostgreSQL (Testcontainers) and exercises the
 * entity mapping + native upsert + due query through the JPA repository. The full
 * Flyway chain is disabled (it stops at V9_18 on an empty database); the table has no
 * foreign key, so only the roles init script is needed.
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NausysSearchSyncRetryRepositoryTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }
    }

    @Autowired
    private lateinit var repository: NausysSearchSyncRetryRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun applyMigration() {
        val sql = ClassPathResource("db/migration/V9_55__nausys_search_sync_retry.sql").inputStream.bufferedReader().readText()
        jdbcTemplate.execute(sql) // IF NOT EXISTS → idempotent
        repository.deleteAll()
    }

    private val from = LocalDate.of(2027, 6, 5)
    private val to = from.plusDays(10)

    private fun due(at: Instant) = repository.findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(at, PageRequest.of(0, 25))

    @Test
    fun `upsert is keyed by interval and the three filter columns, bumps attempts and defers the next attempt`() {
        val now = Instant.now()
        repository.upsert(from, to, "115", "", "", "first", now, now.plus(15, ChronoUnit.MINUTES))
        repository.upsert(from, to, "115", "", "", "second", now, now.plus(15, ChronoUnit.MINUTES))

        val rows = repository.findAll()
        assertEquals(1, rows.size, "same key must bump, not duplicate ('' columns, not NULL)")
        val row = rows.single()
        assertEquals(2, row.attempts)
        assertEquals("second", row.lastError)
        assertEquals(listOf(115L), row.countryIds())
        assertNull(row.regionIds(), "'' reads back as null = no filter")
        assertNull(row.locationIds())
        assertTrue(row.nextAttemptAt!!.isAfter(Instant.now().plus(10, ChronoUnit.MINUTES)), "next attempt must be ~15 min out")

        // A different filter set on the same dates is a different request → second row.
        repository.upsert(from, to, "", "", "7,9", "marinas", now, now.plus(15, ChronoUnit.MINUTES))
        assertEquals(2, repository.findAll().size)
        val marinaRow = repository.findAll().single { it.locations == "7,9" }
        assertEquals(1, marinaRow.attempts)
        assertEquals(listOf(7L, 9L), marinaRow.locationIds())
        assertNull(marinaRow.countryIds())
    }

    @Test
    fun `due query returns only rows whose next attempt has passed, oldest next attempt first`() {
        val now = Instant.now()
        repository.upsert(from, to, "115", "", "", "later", now, now.plus(30, ChronoUnit.MINUTES))
        repository.upsert(from, to.plusDays(1), "115", "", "", "sooner", now, now.plus(15, ChronoUnit.MINUTES))

        assertTrue(due(Instant.now()).isEmpty(), "nothing is due yet")
        assertEquals(listOf("sooner", "later"), due(Instant.now().plus(1, ChronoUnit.HOURS)).map { it.lastError })
        assertEquals(listOf("sooner"), due(Instant.now().plus(20, ChronoUnit.MINUTES)).map { it.lastError })

        val row = repository.findAll().single { it.lastError == "later" }
        row.attempts = 5
        row.nextAttemptAt = Instant.now().minusSeconds(60)
        repository.saveAndFlush(row)
        assertEquals(listOf(row.id), due(Instant.now()).map { it.id })

        repository.deleteById(row.id!!)
        assertEquals(1, repository.findAll().size)
    }
}
