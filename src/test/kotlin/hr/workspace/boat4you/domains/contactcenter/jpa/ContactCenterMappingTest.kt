package hr.workspace.boat4you.domains.contactcenter.jpa

import hr.workspace.boat4you.domains.catalouge.jpa.SerialIdPostgreSQLDialect
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.SiteIds
import hr.workspace.boat4you.domains.settings.jpa.SettingsRepository
import io.kotest.matchers.nulls.shouldBeNull
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * The contact-center entities and the new IRIS chat fields through the real Hibernate mappings on PostgreSQL 18: Hibernate
 * builds a schema from the entities, then the tables the migrations own are dropped and V9_96-V9_98 (plus the chat chain)
 * replace them, so the validator checks every mapping against the real DDL, as ddl-auto validate does on prod.
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
class ContactCenterMappingTest {
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
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var contacts: CcContactRepository

    @Autowired
    private lateinit var visitors: CcVisitorRepository

    @Autowired
    private lateinit var visits: CcVisitRepository

    @Autowired
    private lateinit var pageViews: CcPageViewRepository

    @Autowired
    private lateinit var events: CcEventRepository

    @Autowired
    private lateinit var searches: CcSearchRepository

    @Autowired
    private lateinit var sessions: AiChatSessionRepository

    @Autowired
    private lateinit var messages: AiChatMessageRepository

    @Autowired
    private lateinit var settingsRepository: SettingsRepository

    /** Hibernate's IDENTITY ids have no default under SerialIdPostgreSQLDialect: the real migrations replace its tables. */
    @BeforeAll
    fun migrate() {
        jdbc.execute(
            "DROP TABLE cc_event, cc_page_view, cc_search, cc_visit, cc_contact_tag, cc_visitor, cc_contact, ai_chat_message, ai_chat_session, settings CASCADE",
        )
        ContactCenterTestDb.apply(jdbc, ContactCenterTestDb.CHAT_CHAIN + ContactCenterTestDb.CONTACT_CENTER)
    }

    @Test
    fun `Hibernate validates every mapping against V9_96-V9_98, as ddl-auto validate does on prod`() {
        entityManagerFactory.unwrap(SessionFactory::class.java).schemaManager.validateMappedObjects()
    }

    @Test
    fun `a visit with its page view, event and search round-trips`() {
        val visitor =
            visitors.save(
                CcVisitor().apply {
                    visitorKey = "0f8fad5b-d9cb-469f-a165-70867728950e"
                    firstSiteId = SiteIds.CATAMARAN_GREECE
                },
            )
        val visit =
            visits.save(
                CcVisit().apply {
                    visitorId = visitor.id
                    siteId = SiteIds.CATAMARAN_GREECE
                    countryCode = "GR"
                    channel = VisitChannel.SISTER
                    deviceClass = DeviceClass.MOBILE
                    fromSiteId = SiteIds.BOAT4YOU
                },
            )
        val pv =
            pageViews.save(
                CcPageView().apply {
                    visitId = visit.id
                    path = "/en/catamarans/lagoon-42"
                    pageType = PageType.BOAT
                    boatSlug = "lagoon-42"
                },
            )
        val ev =
            events.save(
                CcEvent().apply {
                    visitId = visit.id
                    pageViewId = pv.id
                    type = CcEventType.CLICK
                    label = "Book online"
                    payload = """{"x":1}"""
                },
            )
        searches.save(
            CcSearch().apply {
                source = SearchSource.AI_CHAT
                siteId = SiteIds.BOAT4YOU
                countryCode = "HR"
                startDate = LocalDate.of(2027, 7, 3)
                resultCount = 0
            },
        )
        visits.findById(visit.id!!).get().let {
            it.countryCode shouldBe "GR"
            it.channel shouldBe VisitChannel.SISTER
            it.deviceClass shouldBe DeviceClass.MOBILE
            it.isBot shouldBe false
            it.pageCount shouldBe 0
        }
        jdbc.queryForObject("SELECT payload->>'x' FROM cc_event WHERE id = ?", String::class.java, ev.id) shouldBe "1"
        visitors.findByVisitorKey("0f8fad5b-d9cb-469f-a165-70867728950e")?.id shouldBe visitor.id
    }

    @Test
    fun `a contact keeps its tags and starts NEW`() {
        val c =
            contacts.save(
                CcContact().apply {
                    name = "Ana"
                    email = "ana@example.com"
                    tags = mutableSetOf("vip", "greece")
                },
            )
        TransactionTemplate(transactionManager).execute { contacts.findById(c.id!!).get().let { it.tags.toSet() to it.status } } shouldBe
            (setOf("vip", "greece") to ContactStatus.NEW)
    }

    @Test
    fun `a new IRIS session is boat4you and not missed, and a message is found by its client id`() {
        val s = sessions.save(AiChatSession().apply { token = "t-map" })
        sessions.findById(s.id!!).get().let {
            it.siteId shouldBe "boat4you"
            it.missed shouldBe false
            it.firstResponseBy.shouldBeNull()
        }
        val m =
            messages.save(
                AiChatMessage().apply {
                    sessionId = s.id
                    role = AiChatMessage.ROLE_USER
                    content = "hi"
                    clientMessageId = "c-0001-abcd"
                    authorUserId = null
                },
            )
        messages.findBySessionIdAndClientMessageId(s.id!!, "c-0001-abcd")?.id shouldBe m.id
        messages.findBySessionIdAndClientMessageId(s.id!!, "c-0002-abcd").shouldBeNull()
    }

    @Test
    fun `a session save never writes missed back (the marker job owns it)`() {
        val s = sessions.save(AiChatSession().apply { token = "t-missed" })
        jdbc.update("UPDATE ai_chat_session SET missed = TRUE WHERE id = ?", s.id)
        sessions.save(s.apply { adminUnread = true }) // this object still says missed = false
        jdbc.queryForObject("SELECT missed FROM ai_chat_session WHERE id = ?", Boolean::class.java, s.id) shouldBe true
    }
}
