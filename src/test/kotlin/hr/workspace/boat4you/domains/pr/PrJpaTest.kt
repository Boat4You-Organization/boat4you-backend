package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.service.PrContactService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base for PR service tests on PostgreSQL 18 (the prod major) with the REAL migrations the PR services touch: V9_56
 * (email_suppression) and V9_121 (the PR tables, with their unique indexes, ON DELETE rules and CHECKs). Hibernate
 * builds nothing (ddl-auto=none); the services run in their own committed transactions (the test is NOT_SUPPORTED),
 * and every test starts from empty PR and suppression tables. Later PR tasks extend this class and add their services
 * to [Import].
 */
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PrContactService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PrJpaTest {
    companion object {
        /** One container for every PR JPA test class in the JVM (never stopped per class), so the cached context keeps its connection. */
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine")
                .apply {
                    withDatabaseName("boat4you_db")
                    withInitScript("init/00_roles.sql")
                }.also { it.start() }

        private val MIGRATIONS = listOf("V9_56__email_suppression_and_campaign.sql", "V9_121__pr_module.sql")
    }

    @Autowired protected lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun migrate() {
        val applied = jdbc.queryForObject("SELECT to_regclass('pr_contact') IS NOT NULL", Boolean::class.java) == true
        if (applied) return
        MIGRATIONS.forEach { file ->
            jdbc.execute("BEGIN; ${ClassPathResource("db/migration/$file").inputStream.readAllBytes().decodeToString()}; COMMIT;")
        }
    }

    @BeforeEach
    fun cleanPrTables() {
        jdbc.execute(
            "TRUNCATE pr_mention, pr_alert_feed, pr_placement, pr_pitch, pr_study, pr_contact, pr_outlet, email_suppression RESTART IDENTITY CASCADE",
        )
    }
}
