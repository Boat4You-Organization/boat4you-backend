package hr.workspace.boat4you.domains.social

import hr.workspace.boat4you.domains.social.jpa.SocialAccount
import hr.workspace.boat4you.domains.social.jpa.SocialAiRun
import hr.workspace.boat4you.domains.social.jpa.SocialAiSetting
import hr.workspace.boat4you.domains.social.jpa.SocialApiKey
import hr.workspace.boat4you.domains.social.jpa.SocialGeneration
import hr.workspace.boat4you.domains.social.jpa.SocialMedia
import hr.workspace.boat4you.domains.social.jpa.SocialOAuthState
import hr.workspace.boat4you.domains.social.jpa.SocialPlatform
import hr.workspace.boat4you.domains.social.jpa.SocialPost
import hr.workspace.boat4you.domains.social.jpa.SocialPostTarget
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import hr.workspace.boat4you.domains.social.service.SocialPublisher
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.OptimisticLockException
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.hibernate.SessionFactory
import org.hibernate.StaleObjectStateException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.tool.schema.spi.SchemaManagementException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate

/**
 * Gate for `spring.jpa.hibernate.ddl-auto: validate` on prod (the V9_94 lesson): the WHOLE Flyway chain
 * (src/main/resources/db/migration, every V and R__ file, V9_118 included) runs on an empty PostgreSQL 18, then Hibernate
 * boots the social entities with `hbm2ddl.auto=validate` and the app's naming strategies, connected as `boat4you_app`
 * like the app (so the V9_118 GRANTs are exercised too).
 *
 * The chain was written against a live database, so an empty one needs three shims, nothing else: V9_18 (a prod data
 * fix) re-links location 2029 to region 5, so both rows are stubbed first (FK targets only); V9_50 reads
 * `reservation_view`, which the repeatable R__1_02 creates and Flyway only runs after all versioned files, so R__1_02 is
 * applied once just before V9_50 (a Flyway callback; Flyway then applies every R__ file itself at the end, as on prod). On prod V9_118
 * runs out of order after 9.120; it creates only its own tables, so the resulting schema is the same.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialHibernateValidateTest {
    private val postgres: PostgreSQLContainer<Nothing> =
        PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
            withDatabaseName("boat4you_db")
            withInitScript("init/00_roles.sql")
        }
    private lateinit var owner: JdbcTemplate
    private var applied = 0

    private val entities =
        listOf(
            SocialAccount::class.java,
            SocialOAuthState::class.java,
            SocialMedia::class.java,
            SocialApiKey::class.java,
            SocialPost::class.java,
            SocialPostTarget::class.java,
            SocialAiSetting::class.java,
            SocialAiRun::class.java,
            SocialGeneration::class.java,
        )

    @BeforeAll
    fun migrateWholeChain() {
        postgres.start()
        owner = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        val flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .callbacks(EmptyDatabaseShims)
                .load()
        flyway.migrate()
        val info = flyway.info()
        applied = info.applied().size
        info.pending().size shouldBe 0
        info.applied().map { it.version?.toString() } shouldContain "9.118"
    }

    @AfterAll
    fun stop() = postgres.stop()

    /** The three shims an empty database needs, run on the migration's own connection right before V9_18 and V9_50. */
    private object EmptyDatabaseShims : Callback {
        override fun supports(
            event: Event,
            context: Context?,
        ) = event == Event.BEFORE_EACH_MIGRATE

        override fun canHandleInTransaction(
            event: Event,
            context: Context?,
        ) = true

        override fun handle(
            event: Event,
            context: Context,
        ) {
            val sql =
                when (context.migrationInfo?.version?.toString()) {
                    "9.18" ->
                        "SET LOCAL session_replication_role = replica; " +
                            "INSERT INTO region (id) VALUES (5); " +
                            "INSERT INTO location (id, name, country_code, country_id) VALUES (2029, 'stub', 'HR', 0); " +
                            "SET LOCAL session_replication_role = origin"
                    "9.50" -> ClassPathResource("db/migration/R__1_02_reservation_view.sql").inputStream.readAllBytes().decodeToString()
                    else -> return
                }
            context.connection.createStatement().use { it.execute(sql) }
        }

        override fun getCallbackName() = "social-test-empty-db-shims"
    }

    /** The social entities only, as the app maps them (Spring Boot's naming strategies, auto-detected dialect). */
    private fun sessionFactory(): SessionFactory {
        val registry =
            StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", postgres.jdbcUrl)
                .applySetting("hibernate.connection.username", "boat4you_app")
                .applySetting("hibernate.connection.password", "testpass")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy::class.java.name)
                .applySetting("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy::class.java.name)
                .build()
        return try {
            MetadataSources(registry).apply { entities.forEach { addAnnotatedClass(it) } }.buildMetadata().buildSessionFactory()
        } catch (e: Exception) {
            StandardServiceRegistryBuilder.destroy(registry)
            throw e
        }
    }

    @Test
    fun `the whole migration chain applies and Hibernate validate accepts every social mapping`() {
        println("SocialHibernateValidateTest: Flyway applied $applied migrations (V + R__), 0 pending; validate on ${entities.size} social entities")
        sessionFactory().use { it.isOpen shouldBe true }
    }

    @Test
    fun `the gate is live - a column type that differs from the mapping fails validate`() {
        owner.execute("ALTER TABLE social_post_target ALTER COLUMN attempts TYPE SMALLINT")
        try {
            val e = shouldThrow<SchemaManagementException> { sessionFactory().close() }
            println("SocialHibernateValidateTest negative check: ${e.message}")
            e.message!! shouldContain "attempts"
        } finally {
            owner.execute("ALTER TABLE social_post_target ALTER COLUMN attempts TYPE INTEGER")
        }
    }

    @Test
    fun `an edit loaded before the publisher's claim cannot put the target back to SCHEDULED`() {
        owner.execute("TRUNCATE social_post_target, social_post, social_account RESTART IDENTITY CASCADE")
        owner.update("INSERT INTO social_account (platform, external_id, name, status) VALUES ('FACEBOOK', 'p1', 'Boat4You', 'CONNECTED')")
        owner.update("INSERT INTO social_post (text, status, source) VALUES ('hi', 'SCHEDULED', 'ADMIN')")
        owner.update("INSERT INTO social_post_target (post_id, account_id, status, due_at) VALUES (1, 1, 'SCHEDULED', now() - interval '1 minute')")
        sessionFactory().use { sf ->
            val session = sf.openSession()
            val tx = session.beginTransaction()
            val target = session.find(SocialPostTarget::class.java, 1L)
            // The publisher claims it meanwhile (its raw UPDATE bumps the version).
            owner.queryForList(SocialPublisher.CLAIM_SQL, Long::class.java, 1) shouldBe listOf(1L)
            target.status = SocialTargetStatus.CANCELLED
            val failure = runCatching { tx.commit() }.exceptionOrNull()
            session.close()
            (generateSequence(failure) { it.cause }.any { it is StaleObjectStateException || it is OptimisticLockException }) shouldBe true
        }
        owner.queryForObject("SELECT status FROM social_post_target WHERE id = 1", String::class.java) shouldBe "PUBLISHING"
    }

    @Test
    fun `boat4you_app may write every social table (GRANTs and sequences)`() {
        sessionFactory().use { sf ->
            sf.inTransaction { s ->
                val a =
                    SocialAccount().apply {
                        platform = SocialPlatform.BLUESKY
                        externalId = "did:plc:grant-check"
                        name = "grant check"
                        meta = mutableMapOf("handle" to "x.bsky.social")
                    }
                s.persist(a)
                s.persist(
                    SocialAiRun().apply {
                        site = "boat4you"
                        runDate = LocalDate.of(2026, 1, 1)
                    },
                )
            }
        }
        owner.queryForObject("SELECT meta->>'handle' FROM social_account WHERE external_id = 'did:plc:grant-check'", String::class.java) shouldBe "x.bsky.social"
    }
}
