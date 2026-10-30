package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * PostgreSQL 18 with the Hibernate-built schema, where the parts V9_112 / V9_113 own are replaced by the real migration
 * files, so every calls test runs against the production column types (V9_94 lesson).
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=hr.workspace.boat4you.domains.catalouge.jpa.SerialIdPostgreSQLDialect",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class CallsJpaTest {
    companion object {
        /** One container for every calls test class in the JVM (started once, never stopped per class), so the cached Spring context keeps its connection. */
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine")
                .apply {
                    withDatabaseName("boat4you_db")
                    withInitScript("init/00_roles.sql")
                }.also { it.start() }

        val INQUIRY_COLUMNS =
            listOf(
                "site",
                "locale",
                "contact_consent",
                "consent_text_version",
                "consent_at",
                "phone_e164",
                "details",
                "do_not_call",
                "offer_sent_at",
                "offer_note",
                "human_takeover_at",
            )
    }

    @Autowired protected lateinit var jdbc: JdbcTemplate

    @Autowired protected lateinit var calls: InquiryCallRepository

    @Autowired protected lateinit var inquiries: InquiryRepository

    @Autowired protected lateinit var txManager: PlatformTransactionManager

    protected val settings: CallsSettings by lazy { CallsSettings(jdbc, Duration.ofSeconds(10)) }

    private fun migration(file: String) = ClassPathResource("db/migration/$file").inputStream.bufferedReader().readText()

    @BeforeAll
    fun migrate() {
        jdbc.execute("DROP TABLE IF EXISTS inquiry_call, call_webhook_event CASCADE")
        // The test dialect makes the Hibernate-built inquiry.yacht_id a NOT NULL serial; in prod (V1_03) it is a nullable FK.
        jdbc.execute("ALTER TABLE inquiry ALTER COLUMN yacht_id DROP NOT NULL, ALTER COLUMN yacht_id DROP DEFAULT")
        INQUIRY_COLUMNS.forEach { jdbc.execute("ALTER TABLE inquiry DROP COLUMN IF EXISTS $it") }
        jdbc.execute("BEGIN; ${migration("V9_112__calls_inquiry_columns.sql")}; COMMIT;")
        jdbc.execute("BEGIN; ${migration("V9_113__calls_tables.sql")}; COMMIT;")
    }

    @BeforeEach
    fun cleanCalls() {
        jdbc.execute("TRUNCATE inquiry_call, call_webhook_event, inquiry RESTART IDENTITY CASCADE")
        jdbc.execute("DELETE FROM settings WHERE name LIKE 'calls.%'")
    }

    protected fun inquiry(
        phone: String? = "+385912345678",
        consent: Boolean = true,
        email: String = "ana@example.hr",
        createdAt: LocalDateTime = LocalDateTime.now(),
        configure: Inquiry.() -> Unit = {},
    ): Inquiry =
        inquiries.saveAndFlush(
            Inquiry().apply {
                this.createdAt = createdAt
                this.email = email
                this.phone = phone ?: "091 234 5678"
                phoneE164 = phone
                contactConsent = consent
                status = InquiryStatus.NEW
                locale = "hr"
                configure()
            },
        )

    /** A reservation_flow row with only email, phone and created_at chosen; every other NOT NULL column gets a dummy of its type. */
    protected fun reservationFlow(
        email: String,
        phone: String,
    ) {
        val required =
            jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'reservation_flow' " +
                    "AND is_nullable = 'NO' AND column_default IS NULL AND column_name NOT IN ('email', 'phone', 'created_at')",
            )
        // Test-only: the row stands alone, so the Hibernate-built foreign keys of reservation_flow are dropped first.
        jdbc
            .queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'reservation_flow'::regclass AND contype = 'f'", String::class.java)
            .forEach { jdbc.execute("ALTER TABLE reservation_flow DROP CONSTRAINT \"$it\"") }
        val names = listOf("email", "phone", "created_at") + required.map { it["column_name"].toString() }
        val values =
            listOf("'$email'", "'$phone'", "now()") +
                required.map {
                    when (it["data_type"].toString()) {
                        "bigint", "integer", "smallint", "numeric", "double precision", "real" -> "0"
                        "boolean" -> "false"
                        "date" -> "current_date"
                        "timestamp without time zone", "timestamp with time zone" -> "now()"
                        "jsonb", "json" -> "'{}'"
                        else -> "'x'"
                    }
                }
        jdbc.update("INSERT INTO reservation_flow (${names.joinToString()}) VALUES (${values.joinToString()})")
    }

    protected fun call(
        inquiry: Inquiry,
        kind: CallKind = CallKind.CONFIRMATION,
        status: CallStatus = CallStatus.SCHEDULED,
        scheduledAt: Instant = Instant.now(),
        attempt: Int = 1,
        configure: InquiryCall.() -> Unit = {},
    ): InquiryCall =
        calls.saveAndFlush(
            InquiryCall().apply {
                inquiryId = inquiry.id
                this.kind = kind
                this.status = status
                this.scheduledAt = scheduledAt
                this.attempt = attempt
                toNumber = inquiry.phoneE164 ?: "+385912345678"
                language = "hr"
                configure()
            },
        )
}
