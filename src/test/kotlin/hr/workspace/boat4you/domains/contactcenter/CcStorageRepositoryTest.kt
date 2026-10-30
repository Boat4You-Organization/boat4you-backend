package hr.workspace.boat4you.domains.contactcenter

import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEvent
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSecurityEventRepository
import hr.workspace.boat4you.domains.contactcenter.service.CcSettings
import hr.workspace.boat4you.domains.contactcenter.service.JdbcCcAiSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant

/**
 * P1c storage: `cc_security_event` (V9_107) and the never-silent columns of `ai_chat_session` (V9_108) through the real
 * Hibernate mappings on PostgreSQL 18. Same pattern as P1a's ContactCenterMappingTest: Hibernate builds a schema from the
 * entities, then the tables the migrations own are dropped and the real chain replaces them.
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=hr.workspace.boat4you.domains.catalouge.jpa.SerialIdPostgreSQLDialect",
    ],
)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CcStorageRepositoryTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }
    }

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var events: CcSecurityEventRepository

    @Autowired
    private lateinit var sessions: AiChatSessionRepository

    /** The real chat, settings and Contact Center migrations replace Hibernate's tables (P1a ContactCenterMappingTest pattern). */
    @BeforeAll
    fun migrate() {
        jdbc.execute(
            "DROP TABLE cc_security_event, cc_event, cc_page_view, cc_search, cc_visit, cc_contact_tag, cc_visitor, cc_contact, " +
                "ai_chat_message, ai_chat_session, settings CASCADE",
        )
        ContactCenterTestDb.apply(jdbc, ContactCenterTestDb.CHAT_CHAIN + ContactCenterTestDb.CONTACT_CENTER) // CONTACT_CENTER now ends with V9_107, V9_108
    }

    @Test
    fun `Hibernate validates the V9_107 and V9_108 mappings`() {
        entityManagerFactory.unwrap(SessionFactory::class.java).schemaManager.validateMappedObjects()
    }

    @Test
    fun `an unknown event type is refused by the database`() {
        shouldThrow<DataIntegrityViolationException> { jdbc.update("INSERT INTO cc_security_event (type, detail) VALUES ('FOO', 'x')") }
    }

    @Test
    fun `the latest breaker event per scope`() {
        jdbc.execute("TRUNCATE cc_security_event")
        events.save(CcSecurityEvent().apply { type = CcSecurityEvent.TYPE_BREAKER_OPEN; detail = "auto: MESSAGES 51/min > 50" })
        events.save(CcSecurityEvent().apply { type = CcSecurityEvent.TYPE_LOCK; siteId = "boat4you"; detail = "session=1 IN:FORMAT:J1" })
        events.save(CcSecurityEvent().apply { type = CcSecurityEvent.TYPE_BREAKER_CLOSE; detail = "auto: below thresholds after 5 min" })
        events.save(CcSecurityEvent().apply { type = CcSecurityEvent.TYPE_BREAKER_OPEN; siteId = "catamaran-greece"; detail = "manual by mario@boat4you.com" })
        events.latestBreakerEventPerScope().associate { (it.siteId ?: "*") to it.type } shouldBe
            mapOf("*" to "BREAKER_CLOSE", "catamaran-greece" to "BREAKER_OPEN")
    }

    @Test
    fun `the holding and return stamps are conditional UPDATEs - once, never on a flagged or taken-over session`() {
        val s = sessions.save(AiChatSession().apply { token = "t-hold"; status = AiChatSession.STATUS_HUMAN_REQUESTED })
        val at = Instant.parse("2027-01-01T10:00:00Z")
        sessions.stampHandoffHolding(s.id!!, at) shouldBe 1
        sessions.stampHandoffHolding(s.id!!, at.plusSeconds(60)) shouldBe 0
        jdbc.update("UPDATE ai_chat_session SET status = 'HUMAN', flagged = TRUE WHERE id = ?", s.id)
        sessions.returnToAi(s.id!!, at) shouldBe 0
        jdbc.update("UPDATE ai_chat_session SET flagged = FALSE WHERE id = ?", s.id)
        sessions.returnToAi(s.id!!, at) shouldBe 1
        sessions.findById(s.id!!).get().let { it.status shouldBe AiChatSession.STATUS_AI; it.aiReturnedAt shouldBe at; it.handoffHoldingAt shouldBe at }
    }

    @Test
    fun `JdbcCcAiSettings writes a row and reads it back at once, through P1a's CcSettings`() {
        val cc = CcSettings(jdbc, Duration.ofSeconds(10))
        val s = JdbcCcAiSettings(cc, jdbc)
        s.update("cc.handoff.minutes", "9") shouldBe true
        s.update("cc.handoff.minutes", "x") shouldBe false
        s.current().handoffMinutes shouldBe 9
        cc.handoffMinutes shouldBe 9 // one key, one cache: P1a's missed-conversation marker sees the same N
        cc.string("cc.handoff.minutes") shouldBe "9" // string() shares the cached read that update()'s reload() filled
        cc.string("cc.nope") shouldBe null
        s.update("cc.takeover.return.minutes", null) shouldBe true
        s.current().takeoverReturnMinutes shouldBe null
        jdbc.update("DELETE FROM settings WHERE name LIKE 'cc.%'")
    }
}
