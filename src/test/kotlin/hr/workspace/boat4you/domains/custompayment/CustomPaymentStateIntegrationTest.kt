package hr.workspace.boat4you.domains.custompayment

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.custompayment.dto.CreateCustomPaymentRequest
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentNotFoundException
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentTooManyException
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentAdminService
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentAlertKind
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentDraft
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentMailer
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentService
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentTx
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentVerdict
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantClient
import hr.workspace.boat4you.domains.payment.revolut.RevolutMerchantOrderData
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrder
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderEventHandler.HandlerResult
import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderTypes
import hr.workspace.boat4you.domains.payment.revolut.RevolutPayment
import hr.workspace.boat4you.domains.payment.revolut.RevolutWebhookEvents
import hr.workspace.boat4you.domains.payment.revolut.revolutProperties
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * custom_payment against a real PostgreSQL 18 (prod: cusma4): the V9_80 migration (run by the container's init, then
 * twice more in a transaction like Flyway — idempotent), Hibernate `ddl-auto=validate` of the entity against it, the
 * CHECK constraints, the state rules of CustomPaymentTx under real row locks, and CustomPaymentService (create, status,
 * webhook events, refunds, reconcile) on top of them with a mocked Revolut client. Only the custom payment entity and
 * repository are in the context: the full Flyway chain cannot run on an empty database (V9_18 is a prod data fix),
 * so the FK target `reservation` is a one-column stand-in.
 */
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CustomPaymentTx::class, CustomPaymentStateIntegrationTest.RecordingMailer::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CustomPaymentStateIntegrationTest {
    companion object {
        private const val MIGRATION = "db/migration/V9_80__custom_payment.sql"
        private val LINK_KEY = "it-".repeat(12)

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withCopyFileToContainer(MountableFile.forClasspathResource("init/00_roles.sql"), "/docker-entrypoint-initdb.d/00_roles.sql")
                withCopyToContainer(
                    Transferable.of("CREATE TABLE reservation (id BIGINT PRIMARY KEY, reservation_number VARCHAR(32));"),
                    "/docker-entrypoint-initdb.d/01_reservation.sql",
                )
                withCopyFileToContainer(MountableFile.forClasspathResource(MIGRATION), "/docker-entrypoint-initdb.d/02_custom_payment.sql")
            }
    }

    @SpringBootConfiguration
    @EntityScan(basePackageClasses = [CustomPayment::class])
    @EnableJpaRepositories(basePackageClasses = [CustomPaymentRepository::class])
    class OnlyCustomPayments

    @Component
    class RecordingMailer : CustomPaymentMailer {
        val receipts = CopyOnWriteArrayList<Long>()
        val officeMails = CopyOnWriteArrayList<Long>()
        val alerts = CopyOnWriteArrayList<Pair<Long, CustomPaymentAlertKind>>()

        fun clear() {
            receipts.clear()
            officeMails.clear()
            alerts.clear()
        }

        override fun receipt(payment: CustomPayment) {
            receipts += payment.id!!
        }

        override fun adminNotification(payment: CustomPayment) {
            officeMails += payment.id!!
        }

        override fun alert(
            payment: CustomPayment,
            kind: CustomPaymentAlertKind,
            reason: String,
        ) {
            alerts += payment.id!! to kind
        }
    }

    @Autowired
    private lateinit var repository: CustomPaymentRepository

    @Autowired
    private lateinit var tx: CustomPaymentTx

    @Autowired
    private lateinit var mailer: RecordingMailer

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val client: RevolutMerchantClient = mock(RevolutMerchantClient::class.java)
    private val reservations: ReservationRepository = mock(ReservationRepository::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    private fun service() = CustomPaymentService(revolutProperties(linkKeys = "boat4you=$LINK_KEY"), tx, repository, reservations, client)

    @BeforeEach
    fun resetMailer() {
        mailer.clear()
    }

    private fun draft(
        reference: String,
        amountMinor: Long = 110_900,
        email: String = "payer@example.com",
    ) = CustomPaymentDraft(
        site = "boat4you",
        reference = reference,
        amountMinor = amountMinor,
        payerEmail = email,
        clientIp = "203.0.113.9",
        locale = "en",
        termsVersion = "2026-10-08",
        acceptedAt = Instant.now(),
        reservationId = null,
    )

    private fun since() = Instant.now().minus(Duration.ofMinutes(50))

    private fun pending(
        reference: String,
        amountMinor: Long = 110_900,
    ): Long = tx.insertPending(draft(reference, amountMinor), since()).payment.id!!

    private fun row(id: Long): CustomPayment = repository.findById(id).orElseThrow()

    private fun order(
        id: String,
        state: String,
        reference: String,
        amount: Long = 110_900,
        checkoutUrl: String? = null,
        refunded: Long? = null,
    ) = RevolutOrder(
        id = id,
        type = "payment",
        state = state,
        amount = amount,
        currency = "EUR",
        outstandingAmount = 0,
        refundedAmount = refunded,
        checkoutUrl = checkoutUrl,
        merchantOrderData = RevolutMerchantOrderData(reference),
        payments = listOf(RevolutPayment("pay-$id", state)),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    @Order(1)
    fun `migration runs twice, the entity validates against it and the app role can write`() {
        val sql = ClassPathResource(MIGRATION).inputStream.bufferedReader().readText()
        // Twice, in a transaction like Flyway (it uses SET LOCAL): the migration must be re-runnable.
        jdbc.execute("BEGIN; $sql; COMMIT;")
        jdbc.execute("BEGIN; $sql; COMMIT;")
        jdbc.queryForObject(
            "SELECT has_table_privilege('boat4you_app', 'custom_payment', 'SELECT, INSERT, UPDATE')",
            Boolean::class.java,
        ) shouldBe true
        jdbc.queryForObject("SELECT has_sequence_privilege('boat4you_app', 'custom_payment_id_seq', 'USAGE')", Boolean::class.java) shouldBe true
        jdbc
            .queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'custom_payment' AND indexname LIKE 'idx_%'", String::class.java)
            .toSet() shouldBe
            setOf("idx_custom_payment_created", "idx_custom_payment_status_created", "idx_custom_payment_email_created", "idx_custom_payment_reservation")
        // ddl-auto=validate already passed when the context started; a JPA round trip on top.
        val id = pending("100116/2027")
        with(row(id)) {
            status shouldBe CustomPaymentStatus.PENDING
            currency shouldBe "EUR"
            provider shouldBe "REVOLUT"
            refundedAmountMinor shouldBe 0L
            publicToken.shouldNotBeNull()
        }
    }

    @Test
    @Order(2)
    fun `CHECK constraints reject what the app must never write`() {
        fun insert(
            site: String = "boat4you",
            reference: String = "CHECK-1",
            amount: Long = 5000,
            currency: String = "EUR",
            status: String = "PENDING",
            refunded: Long = 0,
            note: String? = null,
            orderId: String? = null,
        ) = jdbc.update(
            """
            INSERT INTO custom_payment (public_token, site, reference, amount_minor, currency, status, payer_email, terms_version,
                                        accepted_at, refunded_amount_minor, admin_note, provider_order_id)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'check@example.com', '2026-10-08', now(), ?, ?, ?)
            """.trimIndent(),
            site,
            reference,
            amount,
            currency,
            status,
            refunded,
            note,
            orderId,
        )

        insert() shouldBe 1
        insert(reference = "100116/2027 A_b.c-d", status = "NEEDS_REVIEW", refunded = 5000, note = "n".repeat(2000), orderId = "ord-check") shouldBe 1
        val bad: List<() -> Int> =
            listOf(
                { insert(site = "evil.example") },
                { insert(reference = " 100116/2027") },
                { insert(reference = "ab") },
                { insert(reference = "100116;DROP") },
                { insert(reference = "x".repeat(65)) },
                { insert(amount = 0) },
                { insert(amount = -5000) },
                { insert(currency = "USD") },
                { insert(status = "PAID") },
                { insert(refunded = -1) },
                { insert(refunded = 5001) },
                { insert(note = "n".repeat(2001)) },
                { insert(orderId = "ord-check") },
            )
        bad.forEach { shouldThrow<DataIntegrityViolationException> { it() } }
    }

    @Test
    @Order(3)
    fun `claimCheck - 1 then 0 within 10 s, 1 again after, 0 once the row is no longer PENDING`() {
        val id = pending("CLAIM-1")
        repository.claimCheck(id) shouldBe 1
        repository.claimCheck(id) shouldBe 0
        jdbc.update("UPDATE custom_payment SET last_checked_at = now() - interval '11 seconds' WHERE id = ?", id)
        repository.claimCheck(id) shouldBe 1
        tx.applyVerdict(id, CustomPaymentVerdict.Failed, null) shouldBe CustomPaymentStatus.FAILED
        jdbc.update("UPDATE custom_payment SET last_checked_at = NULL WHERE id = ?", id)
        repository.claimCheck(id) shouldBe 0
    }

    @Test
    @Order(4)
    fun `two concurrent COMPLETE verdicts - exactly one receipt and one office e-mail`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(3) { round ->
                val id = pending("RACE-$round")
                tx.attachOrder(id, "ord-race-$round", "https://checkout.revolut.com/x")
                mailer.clear()
                val start = CountDownLatch(1)
                val results =
                    (1..2).map {
                        pool.submit<CustomPaymentStatus?> {
                            start.await()
                            tx.applyVerdict(id, CustomPaymentVerdict.Complete("pay-1"), """{"id":"ord-race-$round"}""", "ORDER_COMPLETED")
                        }
                    }
                start.countDown()
                results.map { it.get(30, TimeUnit.SECONDS) } shouldContainExactly listOf(CustomPaymentStatus.COMPLETED, CustomPaymentStatus.COMPLETED)
                mailer.receipts shouldContainExactly listOf(id)
                mailer.officeMails shouldContainExactly listOf(id)
                mailer.alerts shouldHaveSize 0
                with(row(id)) {
                    status shouldBe CustomPaymentStatus.COMPLETED
                    completedAt.shouldNotBeNull()
                    providerPaymentId shouldBe "pay-1"
                    orderSnapshot!! shouldContain "ord-race-$round"
                    lastWebhookEvent shouldBe "ORDER_COMPLETED"
                    lastWebhookAt.shouldNotBeNull()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @Order(5)
    fun `FAILED then paid - late COMPLETED with receipt + alert, NEEDS_REVIEW alerts once and never downgrades COMPLETED`() {
        val id = pending("LATE-1")
        tx.applyVerdict(id, CustomPaymentVerdict.Failed, null, "ORDER_FAILED") shouldBe CustomPaymentStatus.FAILED
        tx.applyVerdict(id, CustomPaymentVerdict.Cancelled, null) shouldBe CustomPaymentStatus.FAILED
        tx.applyVerdict(id, CustomPaymentVerdict.StillPending, null) shouldBe CustomPaymentStatus.FAILED
        mailer.receipts shouldHaveSize 0

        tx.applyVerdict(id, CustomPaymentVerdict.Complete("pay-late"), """{"id":"late"}""", "ORDER_COMPLETED") shouldBe CustomPaymentStatus.COMPLETED
        mailer.receipts shouldContainExactly listOf(id)
        mailer.officeMails shouldContainExactly listOf(id)
        mailer.alerts shouldContainExactly listOf(id to CustomPaymentAlertKind.LATE_COMPLETION)

        tx.applyVerdict(id, CustomPaymentVerdict.Complete("pay-late"), null) shouldBe CustomPaymentStatus.COMPLETED
        tx.applyVerdict(id, CustomPaymentVerdict.NeedsReview("amount"), null) shouldBe CustomPaymentStatus.COMPLETED
        tx.applyVerdict(id, CustomPaymentVerdict.Failed, null) shouldBe CustomPaymentStatus.COMPLETED
        mailer.receipts shouldHaveSize 1
        mailer.alerts shouldHaveSize 1

        val review = pending("REVIEW-1")
        mailer.clear()
        tx.applyVerdict(review, CustomPaymentVerdict.NeedsReview("amount 1 instead of 110900"), """{"id":"r"}""") shouldBe CustomPaymentStatus.NEEDS_REVIEW
        tx.applyVerdict(review, CustomPaymentVerdict.NeedsReview("amount 1 instead of 110900"), null) shouldBe CustomPaymentStatus.NEEDS_REVIEW
        tx.applyVerdict(review, CustomPaymentVerdict.Failed, null) shouldBe CustomPaymentStatus.NEEDS_REVIEW
        mailer.alerts shouldContainExactly listOf(review to CustomPaymentAlertKind.NEEDS_REVIEW)
        mailer.receipts shouldHaveSize 0
        row(review).lastCheckedAt.shouldNotBeNull()
    }

    @Test
    @Order(6)
    fun `refunds only ever grow, clamped to the amount, one alert per increase`() {
        val id = pending("REFUND-1", amountMinor = 10_000)
        tx.applyVerdict(id, CustomPaymentVerdict.Complete("pay-r"), null)
        mailer.clear()
        tx.applyRefund(id, 3000) shouldBe 3000L
        tx.applyRefund(id, 2000) shouldBe 3000L
        tx.applyRefund(id, 3000) shouldBe 3000L
        mailer.alerts shouldContainExactly listOf(id to CustomPaymentAlertKind.REFUND)
        tx.applyRefund(id, 25_000) shouldBe 10_000L
        tx.applyRefund(id, 10_000) shouldBe 10_000L
        mailer.alerts shouldHaveSize 2
        row(id).refundedAmountMinor shouldBe 10_000L
        row(id).status shouldBe CustomPaymentStatus.COMPLETED
    }

    @Test
    @Order(7)
    fun `dedupe - the same attempt with a checkout is reused (e-mail case-insensitive), anything different is a new row`() {
        val first = tx.insertPending(draft("DEDUPE-1"), since())
        first.existing shouldBe false
        // No checkout attached yet: not reusable.
        val noCheckout = tx.insertPending(draft("DEDUPE-1"), since())
        noCheckout.existing shouldBe false
        noCheckout.payment.id shouldNotBe first.payment.id

        tx.attachOrder(first.payment.id!!, "ord-dedupe-1", "https://checkout.revolut.com/d1")
        val again = tx.insertPending(draft("DEDUPE-1", email = "  PAYER@example.com".trim()), since())
        again.existing shouldBe true
        again.payment.id shouldBe first.payment.id
        again.payment.checkoutUrl shouldBe "https://checkout.revolut.com/d1"

        tx.insertPending(draft("DEDUPE-1", amountMinor = 110_901), since()).existing shouldBe false
        tx.insertPending(draft("DEDUPE-2"), since()).existing shouldBe false
        tx.insertPending(draft("DEDUPE-1"), Instant.now().plusSeconds(5)).existing shouldBe false
        tx.markCreateFailed(first.payment.id!!)
        row(first.payment.id!!).status shouldBe CustomPaymentStatus.FAILED
        tx.insertPending(draft("DEDUPE-1"), since()).payment.id shouldNotBe first.payment.id
    }

    @Test
    @Order(8)
    fun `service - happy path, identical retry reuses the checkout, 5 attempts per e-mail per hour, status reads Revolut once per 10 s`() {
        var n = 0
        `when`(client.createOrder(any())).thenAnswer {
            n++
            order("ord-svc-$n", "pending", "-", checkoutUrl = "https://checkout.revolut.com/svc-$n")
        }
        val service = service()

        fun request(
            reference: String,
            email: String = "cap@example.com",
        ) = CreateCustomPaymentRequest(
            reference = reference,
            amountMinor = 110_900.toBigDecimal(),
            email = email,
            acceptTerms = true,
            locale = "de",
            site = "boat4you",
            linkKey = LINK_KEY,
        )

        val first = service.create(request("CAP-1"), "203.0.113.7")
        first.redirectUrl shouldBe "https://checkout.revolut.com/svc-1"
        service.create(request("CAP-1"), "203.0.113.7") shouldBe first
        (2..5).forEach { service.create(request("CAP-$it"), "203.0.113.7") }
        shouldThrow<CustomPaymentTooManyException> { service.create(request("CAP-6", email = "CAP@Example.com"), "203.0.113.7") }
        service.create(request("CAP-6", email = "other@example.com"), "203.0.113.7").redirectUrl shouldBe "https://checkout.revolut.com/svc-6"
        verify(client, times(6)).createOrder(any())

        val rows = repository.findAllByPayerEmailIgnoreCase("CAP@example.com")
        rows shouldHaveSize 5
        rows.forEach {
            it.status shouldBe CustomPaymentStatus.PENDING
            it.providerOrderId.shouldNotBeNull()
            it.clientIp shouldBe "203.0.113.7"
            it.locale shouldBe "de"
        }

        val created = repository.findByPublicToken(java.util.UUID.fromString(first.ref))!!
        `when`(client.getOrder(created.providerOrderId!!)).thenReturn(order(created.providerOrderId!!, "completed", first.ref))
        service.status(first.ref).status shouldBe CustomPaymentStatus.COMPLETED
        service.status(first.ref).status shouldBe CustomPaymentStatus.COMPLETED
        verify(client, times(1)).getOrder(created.providerOrderId!!)
        mailer.receipts shouldContainExactly listOf(created.id!!)

        jdbc.update("UPDATE custom_payment SET created_at = now() - interval '31 days' WHERE id = ?", created.id)
        shouldThrow<CustomPaymentNotFoundException> { service.status(first.ref) }
    }

    @Test
    @Order(9)
    fun `reconcile sweep - unattached after 15 min FAILED, expired order FAILED, still pending after 48 h NEEDS_REVIEW + alert`() {
        val unattached = pending("SWEEP-UNATTACHED")
        jdbc.update("UPDATE custom_payment SET created_at = now() - interval '20 minutes' WHERE id = ?", unattached)

        val expired = pending("SWEEP-EXPIRED")
        tx.attachOrder(expired, "ord-sweep-expired", "https://checkout.revolut.com/e")
        `when`(client.getOrder("ord-sweep-expired")).thenReturn(order("ord-sweep-expired", "failed", row(expired).publicToken.toString()))

        val stale = pending("SWEEP-STALE")
        tx.attachOrder(stale, "ord-sweep-stale", "https://checkout.revolut.com/s")
        jdbc.update("UPDATE custom_payment SET created_at = now() - interval '49 hours' WHERE id = ?", stale)
        `when`(client.getOrder("ord-sweep-stale")).thenReturn(order("ord-sweep-stale", "pending", row(stale).publicToken.toString()))

        val refunded = pending("SWEEP-REFUND", amountMinor = 20_000)
        tx.attachOrder(refunded, "ord-sweep-refund", "https://checkout.revolut.com/r")
        `when`(client.getOrder("ord-sweep-refund"))
            .thenReturn(order("ord-sweep-refund", "completed", row(refunded).publicToken.toString(), amount = 20_000, refunded = 5_000))

        service().reconcileSweep()

        row(unattached).status shouldBe CustomPaymentStatus.FAILED
        row(expired).status shouldBe CustomPaymentStatus.FAILED
        row(stale).status shouldBe CustomPaymentStatus.NEEDS_REVIEW
        row(refunded).status shouldBe CustomPaymentStatus.COMPLETED
        row(refunded).refundedAmountMinor shouldBe 5_000L
        mailer.alerts.filter { it.first == stale } shouldContainExactly listOf(stale to CustomPaymentAlertKind.STALE)
        mailer.alerts.filter { it.first == refunded } shouldContainExactly listOf(refunded to CustomPaymentAlertKind.REFUND)
        mailer.receipts shouldContainExactly listOf(refunded)
    }

    @Test
    @Order(10)
    fun `admin - search with and without filters (null parameters typed), money received, note, reservation number`() {
        val admin = CustomPaymentAdminService(repository, reservations, tx, service())
        val all = admin.list(null, null, null, 0, 100)
        all.page.totalElements shouldBe jdbc.queryForObject("SELECT count(*) FROM custom_payment", Long::class.java)
        all.content.map { it.createdAt } shouldBe all.content.map { it.createdAt }.sortedDescending()
        all.totalReceivedMinor shouldBe
            jdbc.queryForObject("SELECT sum(amount_minor - refunded_amount_minor) FROM custom_payment WHERE status = 'COMPLETED'", Long::class.java)

        val refund = admin.list(null, "boat4you", "sweep-refund", 0, 20)
        refund.content.map { it.reference } shouldContainExactly listOf("SWEEP-REFUND")
        refund.totalReceivedMinor shouldBe 15_000L
        admin.list(CustomPaymentStatus.FAILED, null, "sweep-refund", 0, 20).totalReceivedMinor shouldBe 0L
        admin.list(null, "europe-yachts", null, 0, 20).page.totalElements shouldBe 0L
        admin.list(null, null, "CAP@EXAMPLE", 0, 2).let {
            it.content shouldHaveSize 2
            it.page.totalElements shouldBe 5L
            it.page.totalPages shouldBe 3L
        }
        admin.list(null, null, "ord-sweep-stale", 0, 20).content.single().status shouldBe CustomPaymentStatus.NEEDS_REVIEW

        val id = refund.content.single().id
        jdbc.update("INSERT INTO reservation (id, reservation_number) VALUES (1441, '1441012/2026')")
        jdbc.update("UPDATE custom_payment SET reservation_id = 1441 WHERE id = ?", id)
        `when`(reservations.findReservationNumbersByIdIn(setOf(1441L))).thenReturn(listOf(arrayOf<Any?>(1441L, "1441012/2026")))
        with(admin.updateNote(id, "  Paid by the charterer  ")) {
            adminNote shouldBe "Paid by the charterer"
            reservationId shouldBe 1441L
            reservationNumber shouldBe "1441012/2026"
        }
        admin.updateNote(id, "   ").adminNote shouldBe null
        shouldThrow<ParameterValidationException> { admin.updateNote(id, "n".repeat(2001)) }
        shouldThrow<CustomPaymentNotFoundException> { admin.updateNote(999_999, "x") }
        // The FK keeps the payment when a reservation is purged.
        jdbc.update("DELETE FROM reservation WHERE id = 1441")
        row(id).reservationId shouldBe null
    }

    /** A PENDING row with its Revolut order attached, as after a successful create. */
    private fun attached(
        reference: String,
        orderId: String,
        amountMinor: Long = 110_900,
    ): Long = pending(reference, amountMinor).also { tx.attachOrder(it, orderId, "https://checkout.revolut.com/$orderId") }

    @Test
    @Order(11)
    fun `webhook - a declined or failed card attempt only stamps the row and never asks Revolut, an order we do not know is ignored`() {
        val id = attached("WH-DECLINED", "ord-wh-declined")
        val service = service()

        service.onEvent(RevolutWebhookEvents.ORDER_PAYMENT_DECLINED, "ord-wh-declined") shouldBe HandlerResult.HANDLED
        with(row(id)) {
            status shouldBe CustomPaymentStatus.PENDING
            lastWebhookEvent shouldBe RevolutWebhookEvents.ORDER_PAYMENT_DECLINED
            lastWebhookAt.shouldNotBeNull()
            lastCheckedAt.shouldBeNull()
            orderSnapshot.shouldBeNull()
        }
        service.onEvent(RevolutWebhookEvents.ORDER_PAYMENT_FAILED, "ord-wh-declined") shouldBe HandlerResult.HANDLED
        row(id).status shouldBe CustomPaymentStatus.PENDING
        row(id).lastWebhookEvent shouldBe RevolutWebhookEvents.ORDER_PAYMENT_FAILED

        service.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, "ord-wh-nobody") shouldBe HandlerResult.HANDLED
        verifyNoInteractions(client)
        mailer.receipts shouldHaveSize 0
        mailer.officeMails shouldHaveSize 0
        mailer.alerts shouldHaveSize 0
    }

    @Test
    @Order(12)
    fun `webhook - Revolut unreachable or 5xx is TRANSIENT (503, Revolut retries) and leaves the row untouched, an unknown order is stamped`() {
        val id = attached("WH-TRANSIENT", "ord-wh-transient")
        `when`(client.getOrder("ord-wh-transient"))
            .thenThrow(ResourceAccessException("I/O error: connect timed out"), HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
            .thenReturn(null)
        val service = service()

        service.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, "ord-wh-transient") shouldBe HandlerResult.TRANSIENT
        service.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, "ord-wh-transient") shouldBe HandlerResult.TRANSIENT
        with(row(id)) {
            status shouldBe CustomPaymentStatus.PENDING
            lastWebhookEvent.shouldBeNull()
            lastWebhookAt.shouldBeNull()
            lastCheckedAt.shouldBeNull()
            orderSnapshot.shouldBeNull()
            completedAt.shouldBeNull()
        }

        // Revolut answers 404 (client -> null): nothing to decide, the event is stamped and not retried.
        service.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, "ord-wh-transient") shouldBe HandlerResult.HANDLED
        with(row(id)) {
            status shouldBe CustomPaymentStatus.PENDING
            lastWebhookEvent shouldBe RevolutWebhookEvents.ORDER_COMPLETED
            lastWebhookAt.shouldNotBeNull()
        }
        verify(client, times(3)).getOrder("ord-wh-transient")
        mailer.receipts shouldHaveSize 0
        mailer.alerts shouldHaveSize 0
    }

    @Test
    @Order(13)
    fun `webhook - re-reads the order and applies the verdict, completion sends one receipt, a repeated event sends nothing more`() {
        val id = attached("WH-COMPLETE", "ord-wh-complete")
        val token = row(id).publicToken.toString()
        `when`(client.getOrder("ord-wh-complete"))
            .thenReturn(order("ord-wh-complete", "processing", token), order("ord-wh-complete", "completed", token))
        val service = service()

        service.onEvent(RevolutWebhookEvents.ORDER_AUTHORISED, "ord-wh-complete") shouldBe HandlerResult.HANDLED
        row(id).status shouldBe CustomPaymentStatus.PENDING
        row(id).lastWebhookEvent shouldBe RevolutWebhookEvents.ORDER_AUTHORISED
        mailer.receipts shouldHaveSize 0

        service.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, "ord-wh-complete") shouldBe HandlerResult.HANDLED
        with(row(id)) {
            status shouldBe CustomPaymentStatus.COMPLETED
            completedAt.shouldNotBeNull()
            providerPaymentId shouldBe "pay-ord-wh-complete"
            orderSnapshot!! shouldContain "ord-wh-complete"
            lastWebhookEvent shouldBe RevolutWebhookEvents.ORDER_COMPLETED
            lastWebhookAt.shouldNotBeNull()
        }
        mailer.receipts shouldContainExactly listOf(id)
        mailer.officeMails shouldContainExactly listOf(id)

        service.onEvent(RevolutWebhookEvents.ORDER_COMPLETED, "ord-wh-complete") shouldBe HandlerResult.HANDLED
        row(id).status shouldBe CustomPaymentStatus.COMPLETED
        mailer.receipts shouldContainExactly listOf(id)
        mailer.officeMails shouldContainExactly listOf(id)
        mailer.alerts shouldHaveSize 0
        verify(client, times(3)).getOrder("ord-wh-complete")
    }

    @Test
    @Order(14)
    fun `refund webhook - Revolut's running total from the original order, the refund order's amount when that total is unreadable`() {
        fun refundOrder(
            id: String,
            originalOrderId: String,
            amount: Long,
        ) = order(id, "completed", "-", amount = amount).copy(type = RevolutOrderTypes.REFUND, relatedOrderId = originalOrderId)

        fun paid(
            reference: String,
            orderId: String,
        ): Long =
            attached(reference, orderId, amountMinor = 10_000).also {
                tx.applyVerdict(it, CustomPaymentVerdict.Complete("pay-$orderId"), null) shouldBe CustomPaymentStatus.COMPLETED
            }

        val total = paid("WH-REFUND-TOTAL", "ord-wh-refund-total")
        val unreadable = paid("WH-REFUND-UNREADABLE", "ord-wh-refund-unreadable")
        val noTotal = paid("WH-REFUND-NO-TOTAL", "ord-wh-refund-no-total")
        `when`(client.getOrder("ord-wh-refund-total"))
            .thenReturn(order("ord-wh-refund-total", "completed", row(total).publicToken.toString(), amount = 10_000, refunded = 3000))
        `when`(client.getOrder("ord-wh-refund-unreadable")).thenThrow(ResourceAccessException("I/O error: read timed out"))
        `when`(client.getOrder("ord-wh-refund-no-total"))
            .thenReturn(order("ord-wh-refund-no-total", "completed", row(noTotal).publicToken.toString(), amount = 10_000))
        val service = service()
        mailer.clear()

        // The refund order itself says 1000, Revolut's running total on the original order says 3000: the total wins.
        service.onRefund("ord-wh-refund-total", refundOrder("ref-wh-1", "ord-wh-refund-total", 1000))
        row(total).refundedAmountMinor shouldBe 3000L
        service.onRefund("ord-wh-refund-total", refundOrder("ref-wh-1", "ord-wh-refund-total", 1000))
        row(total).refundedAmountMinor shouldBe 3000L
        mailer.alerts.filter { it.first == total } shouldContainExactly listOf(total to CustomPaymentAlertKind.REFUND)

        service.onRefund("ord-wh-refund-unreadable", refundOrder("ref-wh-2", "ord-wh-refund-unreadable", 2500))
        row(unreadable).refundedAmountMinor shouldBe 2500L
        mailer.alerts.filter { it.first == unreadable } shouldContainExactly listOf(unreadable to CustomPaymentAlertKind.REFUND)

        service.onRefund("ord-wh-refund-no-total", refundOrder("ref-wh-3", "ord-wh-refund-no-total", 4000))
        row(noTotal).refundedAmountMinor shouldBe 4000L
        mailer.alerts.filter { it.first == noTotal } shouldContainExactly listOf(noTotal to CustomPaymentAlertKind.REFUND)

        // A refund for an order that is not ours: nothing read, nothing written.
        service.onRefund("ord-wh-refund-nobody", refundOrder("ref-wh-4", "ord-wh-refund-nobody", 500))
        verify(client, times(0)).getOrder("ord-wh-refund-nobody")
        listOf(total, unreadable, noTotal).forEach { row(it).status shouldBe CustomPaymentStatus.COMPLETED }
        mailer.alerts shouldHaveSize 3
        mailer.receipts shouldHaveSize 0
    }
}
