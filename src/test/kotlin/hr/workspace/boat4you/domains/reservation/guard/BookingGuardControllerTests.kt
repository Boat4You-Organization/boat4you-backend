package hr.workspace.boat4you.domains.reservation.guard

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.PartnerPriceGuardMode
import hr.workspace.boat4you.common.errorhandling.ApiErrorCodes
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.reservation.controllers.PublicReservationController
import hr.workspace.boat4you.domains.reservation.controllers.ReservationController
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.OfferNotBookableException
import hr.workspace.boat4you.domains.reservation.exceptions.PartnerPriceChangedException
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.FLOW_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.OFFER_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.WEEK_FROM
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.WEEK_TO
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.YACHT_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.flow
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.offer
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.stub
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.wrapper
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.yacht
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import hr.workspace.boat4you.domains.reservation.service.BookingFailureAlertService
import hr.workspace.boat4you.domains.reservation.service.OfferNotBookableFollowUp
import hr.workspace.boat4you.domains.reservation.service.PartnerPriceGuard
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.invocation.InvocationOnMock
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.Optional

/**
 * WS-C at the booking endpoints: both controllers wired with mocks for the services and the REAL
 * PartnerPeriodCheckStore on PostgreSQL 18 with V9_82 (same minimal-schema approach as RealPriceEvidenceStoresTest).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingGuardControllerTests {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private val MINIMAL_SCHEMA =
            """
            CREATE TABLE yacht (id bigserial PRIMARY KEY, name varchar(255) NOT NULL);
            CREATE TABLE reservation_extras (id bigserial PRIMARY KEY, reservation_id bigint NOT NULL, name varchar(200), price numeric);
            INSERT INTO yacht (id, name) VALUES ($YACHT_ID, 'Euphoria IV');
            """.trimIndent()
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var store: PartnerPeriodCheckStore

    // ---- collaborators
    private var flowServiceAnswer: () -> Long = { FLOW_ID }
    private var partnerWrapper: ReservationResponseWrapper = wrapper("4250.00", "0", "4250.00")
    private var mode = PartnerPriceGuardMode.OFF
    private var bookedFlow: ReservationFlow = flow(offer(yacht(ExternalSystemEnum.MMK), extTotalPrice = BigDecimal("4250.00")))

    private val alertCalls = mutableListOf<InvocationOnMock>()
    private val integrationCalls = mutableListOf<InvocationOnMock>()
    private val mutationCalls = mutableListOf<InvocationOnMock>()

    private val flowService: ReservationFlowMutationService =
        stub { inv -> if (inv.method.name == "createReservationFlow") flowServiceAnswer() else null }
    private val integration: ReservationIntegrationService =
        stub(integrationCalls) { inv -> if (inv.method.name == "createExternalReservation") partnerWrapper else null }
    private val reservationDto: ReservationDto = stub { inv -> if (inv.method.name == "getId") 501L else null }
    private val mutation: ReservationMutationService =
        stub(mutationCalls) { inv -> if (inv.method.name == "createReservation") reservationDto else null }
    private val alerts: BookingFailureAlertService = stub(alertCalls)
    private val switches: RealPriceSwitches = stub { inv -> if (inv.method.name == "getPartnerPriceGuard") mode else null }
    private val flows: ReservationFlowRepository = stub { inv -> if (inv.method.name == "findById") Optional.of(bookedFlow) else null }
    private val txManager: PlatformTransactionManager = BookingGuardFixtures.txManager()
    private val errorHandler = ApiErrorHandler()

    private val request =
        CreateReservationDto(
            yachtId = YACHT_ID,
            offerId = OFFER_ID,
            email = "tom@example.com",
            name = "Tom",
            surname = "Tester",
            phoneNumber = "+385911234567",
            specialRequest = null,
            selectedExtras = emptySet(),
        )

    private fun notBookable(system: ExternalSystemEnum? = ExternalSystemEnum.MMK) =
        OfferNotBookableException(OFFER_ID, YACHT_ID, WEEK_FROM, WEEK_TO, system, "status OPTION")

    private fun publicController(): PublicReservationController =
        PublicReservationController(
            reservationFlowMutationService = flowService,
            reservationIntegrationService = integration,
            reservationMutationService = mutation,
            bookingFailureAlertService = alerts,
            reservationEmailService = stub(),
            paymentPhasesService = stub(),
            offerRepository = stub(),
            voucherService = stub(),
            partnerPriceGuard = PartnerPriceGuard(switches, flows, store, txManager),
            offerNotBookableFollowUp = OfferNotBookableFollowUp(alerts, store),
        )

    private fun securedController(): ReservationController =
        ReservationController(
            reservationFlowMutationService = flowService,
            reservationIntegrationService = integration,
            reservationMutationService = mutation,
            bookingFailureAlertService = alerts,
            reservationEmailService = stub(),
            reservationFlowQueryingService = stub(),
            paymentPhasesService = stub(),
            userRepository = stub(),
            reservationRepository = stub(),
            reservationSyncService = stub(),
            reservationDocumentService = stub(),
            voucherService = stub(),
            partnerPriceGuard = PartnerPriceGuard(switches, flows, store, txManager),
            offerNotBookableFollowUp = OfferNotBookableFollowUp(alerts, store),
        )

    private val book: List<Pair<String, (CreateReservationDto) -> ResponseEntity<ReservationDto>>>
        get() = listOf("public" to publicController()::createGuestReservation, "secured" to securedController()::create)

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(MINIMAL_SCHEMA)
        val migration = ClassPathResource("db/migration/V9_82__real_price_evidence.sql").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $migration; COMMIT;")
        store = PartnerPeriodCheckStore(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE partner_period_check")
        alertCalls.clear()
        integrationCalls.clear()
        mutationCalls.clear()
        flowServiceAnswer = { FLOW_ID }
        mode = PartnerPriceGuardMode.OFF
        partnerWrapper = wrapper("4250.00", "0", "4250.00")
        bookedFlow = flow(offer(yacht(ExternalSystemEnum.MMK), extTotalPrice = BigDecimal("4250.00")))
    }

    private fun calls(
        list: List<InvocationOnMock>,
        name: String,
    ) = list.filter { it.method.name == name }

    // ---- 3006 (BOOKING_C1=ON: createReservationFlow throws OfferNotBookableException)

    @Test
    fun `a week that is gone answers 409 3006, alerts the admins and queues a priority-3 check, on both endpoints`() {
        book.forEach { (_, call) ->
            jdbc.execute("TRUNCATE partner_period_check")
            alertCalls.clear()
            val rejection = notBookable()
            flowServiceAnswer = { throw rejection }

            val thrown = shouldThrow<OfferNotBookableException> { call(request) }

            val alerted = calls(alertCalls, "alertRejectedRequest")
            alerted shouldHaveSize 1
            alerted.single().arguments[0] shouldBe request
            alerted.single().arguments[1] shouldBe rejection
            val row = store.find(ExternalSystemEnum.MMK, YACHT_ID, WEEK_FROM, WEEK_TO).shouldNotBeNull()
            row.priority shouldBe PeriodCheckPriority.ON_DEMAND_OR_BOOKING
            row.lastCheckedOn shouldBe null
            calls(integrationCalls, "createExternalReservation").shouldBeEmpty()

            val response = errorHandler.handleOfferNotBookableException(thrown)
            response.statusCode shouldBe HttpStatus.CONFLICT
            response.body!!.code shouldBe 3006
            response.body!!.message shouldBe ApiErrorCodes.OFFER_NOT_BOOKABLE.message
        }
    }

    @Test
    fun `an existing lower-priority row is raised to 3, never lowered back`() {
        store.upsertPending(ExternalSystemEnum.NAUSYS, YACHT_ID, WEEK_FROM, WEEK_TO, PeriodCheckPriority.SAMPLE)
        flowServiceAnswer = { throw notBookable(ExternalSystemEnum.NAUSYS) }
        shouldThrow<OfferNotBookableException> { publicController().createGuestReservation(request) }
        store.find(ExternalSystemEnum.NAUSYS, YACHT_ID, WEEK_FROM, WEEK_TO)!!.priority shouldBe 3
        store.upsertPending(ExternalSystemEnum.NAUSYS, YACHT_ID, WEEK_FROM, WEEK_TO, PeriodCheckPriority.SYNTHETIC)
        shouldThrow<OfferNotBookableException> { publicController().createGuestReservation(request) }
        store.find(ExternalSystemEnum.NAUSYS, YACHT_ID, WEEK_FROM, WEEK_TO)!!.priority shouldBe 1
    }

    @Test
    fun `a failing alert or store never changes the 3006 outcome`() {
        val brokenAlerts: BookingFailureAlertService = stub { throw IllegalStateException("smtp down") }
        val brokenStore: PartnerPeriodCheckStore = stub { throw IllegalStateException("db down") }
        flowServiceAnswer = { throw notBookable() }
        val controller =
            PublicReservationController(
                flowService, integration, mutation, brokenAlerts, stub(), stub(), stub(), stub(),
                PartnerPriceGuard(switches, flows, store, txManager), OfferNotBookableFollowUp(brokenAlerts, brokenStore),
            )
        shouldThrow<OfferNotBookableException> { controller.createGuestReservation(request) }
    }

    @Test
    fun `BOOKING_C1 OFF - today's IllegalArgumentException, no alert, no period row`() {
        book.forEach { (_, call) ->
            flowServiceAnswer = { throw IllegalArgumentException("Offer is not available for reservation") }
            shouldThrow<IllegalArgumentException> { call(request) }
        }
        alertCalls.shouldBeEmpty()
        jdbc.queryForObject("SELECT count(*) FROM partner_period_check", Int::class.java) shouldBe 0
    }

    // ---- 3007 (PARTNER_PRICE_GUARD)

    @Test
    fun `higher partner price in ENFORCE - option released by wrapper, flow abandoned, admins alerted, 409 3007`() {
        book.forEach { (_, call) ->
            integrationCalls.clear()
            mutationCalls.clear()
            alertCalls.clear()
            mode = PartnerPriceGuardMode.ENFORCE
            partnerWrapper = wrapper("5000.00", "0", "5000.00")

            val thrown = shouldThrow<PartnerPriceChangedException> { call(request) }

            val released = calls(integrationCalls, "cancelExternalOptionByWrapper").single()
            released.arguments[0] shouldBe FLOW_ID
            released.arguments[1] shouldBe partnerWrapper
            calls(integrationCalls, "deleteExternalReservation").shouldBeEmpty()
            calls(mutationCalls, "createReservation").shouldBeEmpty()
            calls(mutationCalls, "abandonFailedFlow").single().arguments[0] shouldBe FLOW_ID
            val alert = calls(alertCalls, "alertFailedFlow").single()
            alert.arguments[0] shouldBe FLOW_ID
            alert.arguments[1] shouldBe thrown
            alert.arguments[2] shouldBe true

            val response = errorHandler.handlePartnerPriceChangedException(thrown)
            response.statusCode shouldBe HttpStatus.CONFLICT
            response.body!!.code shouldBe 3007
        }
        store.find(ExternalSystemEnum.MMK, YACHT_ID, WEEK_FROM, WEEK_TO)!!.priority shouldBe 3
    }

    @Test
    fun `higher partner price in ALERT proceeds with the same wrapper`() {
        mode = PartnerPriceGuardMode.ALERT
        partnerWrapper = wrapper("5000.00", "0", "5000.00")
        book.forEach { (_, call) -> call(request).statusCode shouldBe HttpStatus.OK }
        calls(mutationCalls, "createReservation").map { it.arguments[1] } shouldBe listOf(partnerWrapper, partnerWrapper)
        calls(integrationCalls, "cancelExternalOptionByWrapper").shouldBeEmpty()
        calls(mutationCalls, "abandonFailedFlow").shouldBeEmpty()
    }

    @Test
    fun `lower partner price in ENFORCE proceeds and the customer's total is untouched`() {
        mode = PartnerPriceGuardMode.ENFORCE
        partnerWrapper = wrapper("4000.00", "0", "4000.00")
        book.forEach { (_, call) -> call(request).statusCode shouldBe HttpStatus.OK }
        bookedFlow.calculatedTotalPrice shouldBe BigDecimal("4800.00")
        calls(mutationCalls, "createReservation").map { it.arguments[1] } shouldBe listOf(partnerWrapper, partnerWrapper)
        // createReservation gets no admin override and no price of ours: the partner wrapper as it came
        calls(mutationCalls, "createReservation").forEach { it.arguments[2] shouldBe null }
    }

    @Test
    fun `auto-confirmed partner RESERVATION above tolerance in ENFORCE is kept (alert-only)`() {
        mode = PartnerPriceGuardMode.ENFORCE
        partnerWrapper = wrapper("9000.00", "0", "9000.00").copy(calculatedSysStatus = ReservationStatus.RESERVATION)
        publicController().createGuestReservation(request).statusCode shouldBe HttpStatus.OK
        calls(integrationCalls, "cancelExternalOptionByWrapper").shouldBeEmpty()
    }

    @Test
    fun `PARTNER_PRICE_GUARD OFF books exactly as today and never reads the flow`() {
        val flowReads = mutableListOf<InvocationOnMock>()
        val countingFlows: ReservationFlowRepository = stub(flowReads) { inv -> if (inv.method.name == "findById") Optional.of(bookedFlow) else null }
        partnerWrapper = wrapper("9000.00", "0", "9000.00")
        val controller =
            PublicReservationController(
                flowService, integration, mutation, alerts, stub(), stub(), stub(), stub(),
                PartnerPriceGuard(switches, countingFlows, store, txManager), OfferNotBookableFollowUp(alerts, store),
            )
        controller.createGuestReservation(request).statusCode shouldBe HttpStatus.OK
        flowReads.shouldBeEmpty()
        alertCalls.shouldBeEmpty()
    }
}
