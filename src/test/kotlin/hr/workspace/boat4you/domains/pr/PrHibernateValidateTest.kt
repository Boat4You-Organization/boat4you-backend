package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.jpa.PrAlertFeed
import hr.workspace.boat4you.domains.pr.jpa.PrContact
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrMention
import hr.workspace.boat4you.domains.pr.jpa.PrMentionRepository
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrPitch
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.jpa.PrPlacement
import hr.workspace.boat4you.domains.pr.jpa.PrStudy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.hibernate.SessionFactory
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
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Gate for `spring.jpa.hibernate.ddl-auto: validate` on prod: the WHOLE Flyway chain (src/main/resources/db/migration,
 * every V and R__ file, V9_121 included) runs on an empty PostgreSQL 18, then Hibernate boots the PR entities with
 * `hbm2ddl.auto=validate` and the app's naming strategies, connected as `boat4you_app` like the app (so the V9_121
 * GRANTs and sequence grants are exercised too). The empty-database shims are the same as in SocialHibernateValidateTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrHibernateValidateTest {
    private val postgres: PostgreSQLContainer<Nothing> =
        PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
            withDatabaseName("boat4you_db")
            withInitScript("init/00_roles.sql")
        }
    private lateinit var owner: JdbcTemplate
    private var applied = 0

    private val entities =
        listOf(
            PrOutlet::class.java,
            PrContact::class.java,
            PrStudy::class.java,
            PrPitch::class.java,
            PrPlacement::class.java,
            PrAlertFeed::class.java,
            PrMention::class.java,
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
        info.applied().map { it.version?.toString() } shouldContain "9.121"
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

        override fun getCallbackName() = "pr-test-empty-db-shims"
    }

    /** The PR entities only, as the app maps them (Spring Boot's naming strategies, auto-detected dialect). */
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
    fun entitiesMatchSchema() {
        println("PrHibernateValidateTest: Flyway applied $applied migrations (V + R__), 0 pending; validate on ${entities.size} PR entities")
        sessionFactory().use { it.isOpen shouldBe true }
    }

    @Test
    fun `the gate is live - a column type that differs from the mapping fails validate`() {
        owner.execute("ALTER TABLE pr_study ALTER COLUMN number TYPE SMALLINT")
        try {
            val e = shouldThrow<SchemaManagementException> { sessionFactory().close() }
            println("PrHibernateValidateTest negative check: ${e.message}")
            e.message!! shouldContain "number"
        } finally {
            owner.execute("ALTER TABLE pr_study ALTER COLUMN number TYPE INT")
        }
    }

    @Test
    fun `boat4you_app may write every PR table through the mappings (GRANTs and sequences)`() {
        sessionFactory().use { sf ->
            sf.inTransaction { s ->
                val outlet =
                    PrOutlet().apply {
                        name = "Grant Check Times"
                        market = "GB"
                        tier = 1
                    }
                s.persist(outlet)
                val contact =
                    PrContact().apply {
                        outletId = outlet.id
                        email = "grant.check@times.co.uk"
                        market = "GB"
                        emailFoundOn = LocalDate.of(2026, 11, 4)
                    }
                s.persist(contact)
                val study =
                    PrStudy().apply {
                        number = 9001
                        siteId = "boat4you"
                        title = "Grant check study"
                    }
                s.persist(study)
                val pitch =
                    PrPitch().apply {
                        studyId = study.id
                        contactId = contact.id
                        template = PrPitchTemplate.DATA_PITCH
                        subject = "s"
                        body = "b"
                    }
                s.persist(pitch)
                s.persist(
                    PrPlacement().apply {
                        studyId = study.id
                        outletId = outlet.id
                        pitchId = pitch.id
                        articleUrl = "https://example.com/grant-check"
                        domainRank = BigDecimal("4.52")
                    },
                )
                val feed =
                    PrAlertFeed().apply {
                        name = "Grant check feed"
                        url = "https://example.com/feed.xml"
                    }
                s.persist(feed)
                s.persist(
                    PrMention().apply {
                        url = "https://example.com/mention"
                        source = "RSS"
                        feedId = feed.id
                        foundAt = Instant.now()
                    },
                )
            }
        }
        owner.queryForObject("SELECT status FROM pr_contact WHERE email = 'grant.check@times.co.uk'", String::class.java) shouldBe
            PrContactStatus.ACTIVE.name
        owner.queryForObject("SELECT status FROM pr_pitch WHERE subject = 's'", String::class.java) shouldBe PrPitchStatus.DRAFT.name
        owner.queryForObject("SELECT link_state FROM pr_placement", String::class.java) shouldBe PrLinkState.NOT_CHECKED.name
        owner.queryForObject("SELECT market FROM pr_outlet WHERE name = 'Grant Check Times'", String::class.java) shouldBe "GB"
    }

    @Test
    fun `repository queries run - email match ignores case, markSending takes a draft once, existsByUrl`() {
        owner.update("INSERT INTO pr_outlet (name, market, tier) VALUES ('Repo Check A', 'GB', 1), ('Repo Check B', 'US', 2)")
        owner.update(
            "INSERT INTO pr_contact (outlet_id, email) SELECT id, 'repo.check@example.com' FROM pr_outlet WHERE name LIKE 'Repo Check %'",
        )
        owner.update("INSERT INTO pr_study (number, site_id, title) VALUES (9002, 'boat4you', 'Repo check study')")
        owner.update(
            "INSERT INTO pr_pitch (study_id, contact_id, template, subject, body) SELECT s.id, c.id, 'DATA_PITCH', 'repo check', 'b' " +
                "FROM pr_study s, pr_contact c WHERE s.number = 9002 AND c.email = 'repo.check@example.com' LIMIT 1",
        )
        owner.update("INSERT INTO pr_mention (url, source, found_at) VALUES ('https://example.com/repo-check', 'RSS', now())")
        val pitchId = owner.queryForObject("SELECT id FROM pr_pitch WHERE subject = 'repo check'", Long::class.java)!!
        sessionFactory().use { sf ->
            val em = sf.createEntityManager()
            try {
                val factory = JpaRepositoryFactory(em)
                val contacts = factory.getRepository(PrContactRepository::class.java)
                val pitches = factory.getRepository(PrPitchRepository::class.java)
                val mentions = factory.getRepository(PrMentionRepository::class.java)
                contacts.findAllByEmailIgnoreCase("Repo.Check@Example.com").size shouldBe 2
                em.transaction.begin()
                pitches.markSending(pitchId) shouldBe 1
                pitches.markSending(pitchId) shouldBe 0
                em.transaction.commit()
                mentions.existsByUrl("https://example.com/repo-check") shouldBe true
                mentions.existsByUrl("https://example.com/other") shouldBe false
            } finally {
                em.close()
            }
        }
        owner.queryForObject("SELECT status FROM pr_pitch WHERE id = ?", String::class.java, pitchId) shouldBe PrPitchStatus.SENDING.name
    }
}
