package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.services.CharterAgreementService
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestAuthentication
import org.openapitools.client.nausys.model.RestYachtReservation
import org.openapitools.client.nausys.model.RestYachtReservationsRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDateTime

/**
 * The swap check against a real database, outside any transaction, as the hourly job runs it on cusma3 (3.11.2026).
 * Plain objects hid that it had never worked: the log line read a lazy field of the partner system and the customer
 * email read the previous yacht and its lazy model, so every detected swap ended in the loop's error counter.
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=hr.workspace.boat4you.domains.catalouge.jpa.SerialIdPostgreSQLDialect",
        "server.host=https://api.example.test",
        "server.host-public=https://www.example.test",
        "server.host-admin-public=https://admin.example.test",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ReservationSyncService::class, ReservationEmailService::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationSyncSwapJpaTest {
    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine")
                .apply {
                    withDatabaseName("boat4you_db")
                    withInitScript("init/00_roles.sql")
                }.also { it.start() }
    }

    @MockitoBean private lateinit var partner: NauSysRetryableClient

    @MockitoBean private lateinit var auth: NauSysAuthProvider

    @MockitoBean private lateinit var mmk: MmkRetryableClient

    @MockitoBean private lateinit var emails: EmailService

    @MockitoBean private lateinit var agreements: CharterAgreementService

    @MockitoBean private lateinit var settings: AdminSettingsService

    @Autowired private lateinit var service: ReservationSyncService

    @Autowired private lateinit var reservations: ReservationRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * The parts Hibernate cannot build as production has them: the audit table from its migration (V1_50, BIGSERIAL id,
     * TIMESTAMP columns) and the marina label, a generated column in production read through @Formula.
     */
    @BeforeAll
    fun productionSchemaParts() {
        jdbc.execute("DROP TABLE reservation_yacht_swap_audit")
        jdbc.execute(ClassPathResource("db/migration/V1_50__reservation_yacht_swap_audit.sql").getContentAsString(Charsets.UTF_8))
        jdbc.execute("ALTER TABLE location ADD COLUMN display_name VARCHAR(255)")
    }

    @BeforeEach
    fun fixtures() {
        jdbc.execute(
            "TRUNCATE reservation_yacht_swap_audit, reservation, reservation_flow, external_mapping, yacht, agency_source, agency, " +
                "model, manufacturer, location, country, external_system, users CASCADE",
        )
        jdbc.execute(
            """
            INSERT INTO external_system (id, name) VALUES (2, 'NauSys');
            INSERT INTO country (id, name, code2, code3, continent) VALUES (1, 'Croatia', 'HR', 'HRV', 'Europe');
            INSERT INTO location (id, name, country_id, country_code, inland) VALUES (10, 'Marina Split', 1, 'HR', false);
            INSERT INTO manufacturer (id, name) VALUES (1, 'Beneteau');
            INSERT INTO model (id, name, manufacturer_id) VALUES (1, 'Oceanis 40.1', 1), (2, 'Lagoon 42', 1);
            INSERT INTO agency (id, name, active, availability_blocked) VALUES (5, 'Agency A', true, false), (7, 'Agency B', true, false);
            INSERT INTO agency_source (agency_id, external_system_id, external_id, "primary") VALUES (5, 2, 555, true), (7, 2, 777, true);
            INSERT INTO yacht (id, name, agency_id, model_id, location_id, main_image_id, entry_type, vessel_type, sys_active) VALUES
              (100, 'Tria', 5, 2, 10, 1, 'UNKNOWN', 'OTHER', true),
              (24660, 'Namaka Ekolu', 5, 1, 10, 2, 'UNKNOWN', 'OTHER', false),
              (19986, 'Namaka Ekolu', 7, 1, 10, 3, 'UNKNOWN', 'OTHER', true);
            INSERT INTO external_mapping (external_id, system_id, type, external_system_id) VALUES
              (555100, 100, 'Yacht', 2), (67732855, 24660, 'Yacht', 2), (67732855, 19986, 'Yacht', 2);
            INSERT INTO users (id, name, surname, email, password, created, entity_status, registration_status, invite_status,
              login_attempts, verification_attempts, marketing_opt_out, password_set)
              VALUES (1, 'Ana', 'Horvat', 'ana@example.com', 'x', LOCALTIMESTAMP, 'ACTIVE', 'STARTED', 'NOT_INVITED', 0, 0, false, false);
            INSERT INTO reservation_flow (id, yacht_id, created_by, user_id, email, name, surname, phone, calculated_total_price, created_at)
              VALUES (9, 100, 1, 1, 'ana@example.com', 'Ana', 'Horvat', '+385911234567', 1000, now());
            INSERT INTO reservation (id, reservation_flow_id, date_from, date_to, base_price, client_price, total_price, created_at,
              status, sys_status, external_id, location_from, location_to, reservation_number)
              VALUES (1, 9, LOCALTIMESTAMP + interval '30 days', LOCALTIMESTAMP + interval '37 days', 1000, 1000, 1000, now(),
              'RESERVED', 'RESERVATION', 777001, 10, 10, '100001/2027');
            """.trimIndent(),
        )
        `when`(auth.auth).thenReturn(RestAuthentication())
    }

    private fun partnerSaysTheReservationIsOn(partnerYachtId: Long) {
        `when`(partner.getReservation(RestYachtReservationsRequest(credentials = RestAuthentication(), reservations = listOf(777001L))))
            .thenReturn(RestYachtReservation(yachtId = partnerYachtId))
    }

    /** The reservation exactly as the hourly job gets it: loaded by its own query, no session left open. */
    private fun asTheJobSeesIt() =
        reservations
            .findActiveForPartnerSync(listOf(ReservationStatus.RESERVATION), LocalDateTime.now().minusDays(7))
            .single()

    private fun sentEmails() =
        mockingDetails(emails).invocations.filter { it.method.name == "sendEmail" }.map {
            @Suppress("UNCHECKED_CAST")
            Triple(it.arguments[0] as List<String>, it.arguments[1] as String, it.arguments[3] as Map<String, Any?>)
        }

    @Test
    fun `a swap to a boat with a retired twin moves the reservation to the active row and emails the new boat`() {
        partnerSaysTheReservationIsOn(67732855)

        service.processReservation(asTheJobSeesIt()) shouldBe ReservationSyncService.ProcessResult.SWAP_DETECTED

        jdbc.queryForObject("SELECT yacht_id FROM reservation_flow WHERE id = 9", Long::class.java) shouldBe 19986L
        jdbc.queryForList("SELECT action || ':' || new_yacht_id FROM reservation_yacht_swap_audit", String::class.java) shouldBe
            listOf("AUTO_UPDATED:19986")
        val (to, subject, variables) = sentEmails().first()
        to shouldBe listOf("Ana Horvat <ana@example.com>")
        subject shouldBe "Your yacht has been replaced on reservation 100001/2027"
        variables["yachtModel"] shouldBe "Oceanis 40.1"
        variables["viewBoatUrl"] shouldBe "https://www.example.test/boat/19986"
        variables["locationFrom"] shouldBe "Marina Split"
    }

    @Test
    fun `the same boat at the partner is checked and nothing is moved or sent`() {
        partnerSaysTheReservationIsOn(555100)

        service.processReservation(asTheJobSeesIt()) shouldBe ReservationSyncService.ProcessResult.CHECKED

        jdbc.queryForObject("SELECT yacht_id FROM reservation_flow WHERE id = 9", Long::class.java) shouldBe 100L
        sentEmails().shouldBeEmpty()
    }
}
