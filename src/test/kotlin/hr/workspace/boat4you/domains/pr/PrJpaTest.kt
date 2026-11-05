package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.pr.links.PrLinkCheckService
import hr.workspace.boat4you.domains.pr.links.PrPageFetcher
import hr.workspace.boat4you.domains.pr.mentions.PrMentionService
import hr.workspace.boat4you.domains.pr.service.OpenPageRankClient
import hr.workspace.boat4you.domains.pr.service.PrContactService
import hr.workspace.boat4you.domains.pr.service.PrMailSender
import hr.workspace.boat4you.domains.pr.service.PrOverviewService
import hr.workspace.boat4you.domains.pr.service.PrPitchService
import hr.workspace.boat4you.domains.pr.service.PrPlacementService
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * THE one Spring test context of every DB-backed PR test, on PostgreSQL 18 (the prod major) with the REAL migrations
 * the PR services touch: V9_56 (email_suppression) and V9_121 (the PR tables, with their unique indexes, ON DELETE rules
 * and CHECKs). Hibernate builds nothing (ddl-auto=none); the services run in their own committed transactions (the test
 * is NOT_SUPPORTED), and every test starts from empty PR and suppression tables.
 *
 * Every PR service/component outside `web` is built by Spring with its real constructor and the real PrProperties
 * binding from application.yml (so the mail kill switch is off, as in prod by default). The only stand-ins, defined once
 * here: [TestClock] (the Clock of every clock-taking service), and Mockito mocks of [PrPageFetcher] (no network) and
 * [OpenPageRankClient]. PrMailSender keeps its default SMTP factory; nothing can send because mail is disabled, and
 * PrSendTest builds its own mail-enabled service around a mocked JavaMailSender.
 *
 * Subclasses add NO @Import, @MockBean, @TestPropertySource or @Bean of their own: any of those would fork a second
 * context (each costs heap, and the full suite runs out of it). A test that needs other mock behaviour stubs the shared
 * mock after Mockito reset in its @BeforeEach.
 */
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    PrJpaTest.Beans::class,
    BrandRegistry::class,
    PrContactService::class,
    PrStudyService::class,
    PrMailSender::class,
    PrPitchService::class,
    PrPlacementService::class,
    PrLinkCheckService::class,
    PrMentionService::class,
    PrOverviewService::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PrJpaTest {
    /** "now" as the test sets it (every test class sets it in @BeforeEach), so time rules are checked without waiting. */
    class TestClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @TestConfiguration
    class Beans {
        @Bean
        fun clock() = TestClock(Instant.parse("2026-11-05T10:00:00Z"))

        @Bean
        fun prPageFetcher(): PrPageFetcher = mock(PrPageFetcher::class.java)

        @Bean
        fun openPageRankClient(): OpenPageRankClient = mock(OpenPageRankClient::class.java)
    }

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
