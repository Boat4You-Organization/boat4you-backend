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
import kotlin.test.assertTrue

/**
 * Runs the real V9_54 script against PostgreSQL (Testcontainers) and exercises
 * the entity mapping + native upsert + due query through the JPA repository.
 * The full Flyway chain is disabled here: on an empty database it stops at
 * V9_18 (a prod data-fix that assumes seeded regions), so only the FK target
 * table comes from `init/01_nausys_offer_sync_retry.sql`.
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NausysOfferSyncRetryRepositoryTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/01_nausys_offer_sync_retry.sql")
            }
    }

    @Autowired
    private lateinit var repository: NausysOfferSyncRetryRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun applyMigration() {
        val sql = ClassPathResource("db/migration/V9_54__nausys_offer_sync_retry.sql").inputStream.bufferedReader().readText()
        jdbcTemplate.execute(sql) // IF NOT EXISTS → idempotent
    }

    private fun newAgency(): Long {
        jdbcTemplate.update("INSERT INTO agency (name) VALUES ('Retry queue test')")
        return jdbcTemplate.queryForObject("SELECT max(id) FROM agency", Long::class.java)!!
    }

    @Test
    fun `upsert is keyed by agency and interval, bumps attempts and defers the next attempt`() {
        val agencyId = newAgency()
        val from = LocalDate.of(2027, 6, 5)
        val to = from.plusWeeks(1)

        val now = Instant.now()
        repository.upsert(agencyId, from, to, "101,102", false, "first", now, now.plus(15, ChronoUnit.MINUTES))
        repository.upsert(agencyId, from, to, "101,102", true, "second", now, now.plus(15, ChronoUnit.MINUTES))

        val rows = repository.findAll().filter { it.agencyId == agencyId }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(2, row.attempts)
        assertEquals("second", row.lastError)
        assertTrue(row.skipDisappearance)
        assertEquals(listOf(101L, 102L), row.yachtExternalIdList())
        assertTrue(row.nextAttemptAt!!.isAfter(Instant.now().plus(10, ChronoUnit.MINUTES)), "next attempt must be ~15 min out")

        assertTrue(repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant.now(), PageRequest.of(0, 500)).none { it.agencyId == agencyId })
        val due = repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant.now().plus(1, ChronoUnit.HOURS), PageRequest.of(0, 500))
        assertEquals(listOf(row.id), due.filter { it.agencyId == agencyId }.map { it.id })

        row.attempts = 5
        row.nextAttemptAt = Instant.now().minusSeconds(60)
        repository.saveAndFlush(row)
        assertEquals(listOf(row.id), repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant.now(), PageRequest.of(0, 500)).map { it.id })
        repository.deleteById(row.id!!)
        assertTrue(repository.findAll().none { it.agencyId == agencyId })
    }
}
