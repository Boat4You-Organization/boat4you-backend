package hr.workspace.boat4you.domains.payment.revolut.booking

import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.CardOrderPromoteState
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.revolut.booking.jpa.ReservationCardOrderStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * reservation_card_order + the new reservation_payment_phase columns against a real PostgreSQL 18 (prod: cusma4): the
 * V9_81 migration on top of the phase table's own migrations (V1_18, V1_22, V1_56; run by the container's init, then
 * V9_81 twice more in a transaction like Flyway — idempotent), Hibernate `ddl-auto=validate` of the ledger entity, the
 * CHECK / UNIQUE rules, the repository queries (reuse before create, pending per flow, claimCheck, reconcile
 * candidates, row locks with lock_timeout) and ReservationCardOrderTx under real locks. Only the ledger entity is in
 * the context (the full Flyway chain cannot run on an empty database — V9_18 is a prod data fix), so reservation and
 * reservation_flow are one-column stand-ins.
 */
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ReservationCardOrderTx::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReservationCardOrderIntegrationTest {
    companion object {
        private const val MIGRATION = "db/migration/V9_81__reservation_card_order.sql"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withCopyFileToContainer(MountableFile.forClasspathResource("init/00_roles.sql"), "/docker-entrypoint-initdb.d/00_roles.sql")
                withCopyToContainer(
                    Transferable.of(
                        """
                        CREATE TABLE reservation (id BIGINT PRIMARY KEY);
                        CREATE TABLE reservation_flow (id BIGINT PRIMARY KEY);
                        INSERT INTO reservation (id) VALUES (42), (43);
                        INSERT INTO reservation_flow (id) VALUES (900), (901);
                        """.trimIndent(),
                    ),
                    "/docker-entrypoint-initdb.d/01_standins.sql",
                )
                listOf(
                    "V1_18__add_payment_phases.sql",
                    "V1_22__add_payment_id_to_payment_phases.sql",
                    "V1_56__unique_stripe_session_id.sql",
                    "V9_81__reservation_card_order.sql",
                ).forEachIndexed { index, file ->
                    withCopyFileToContainer(MountableFile.forClasspathResource("db/migration/$file"), "/docker-entrypoint-initdb.d/0${index + 2}_$file")
                }
            }
    }

    @SpringBootConfiguration
    @EntityScan(basePackageClasses = [ReservationCardOrder::class])
    @EnableJpaRepositories(basePackageClasses = [ReservationCardOrderRepository::class])
    class OnlyCardOrders

    @Autowired
    private lateinit var orders: ReservationCardOrderRepository

    @Autowired
    private lateinit var tx: ReservationCardOrderTx

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private fun plan(
        flowId: Long = 900,
        phaseIds: String = "7",
        amountMinor: Long = 281_160,
        paymentPhaseId: Long? = 7,
        expiresAfter: Duration = Duration.ofHours(1),
    ) = BookingOrderPlan(
        reservationId = if (flowId == 900L) 42 else 43,
        reservationFlowId = flowId,
        reference = "1441002/2026",
        phaseIds = phaseIds,
        payFullAmount = null,
        paymentPhaseId = paymentPhaseId,
        baseAmount = BigDecimal("2677.60"),
        surchargeAmount = BigDecimal("134"),
        amountMinor = amountMinor,
        idempotencyKey = "key-1",
        expiresAfter = expiresAfter,
        expiresAt = Instant.now().plus(expiresAfter),
    )

    private fun insert(
        orderId: String,
        plan: BookingOrderPlan = plan(),
    ): ReservationCardOrder {
        val inserted = tx.insertPending(plan, orderId, "https://checkout.revolut.com/payment-link/$orderId")
        inserted.existing shouldBe false
        return inserted.order
    }

    private fun row(id: Long): ReservationCardOrder = orders.findById(id).orElseThrow()

    private fun reusable(plan: BookingOrderPlan) =
        orders.findReusable(plan.reservationFlowId, plan.phaseIds, plan.amountMinor, ReservationCardOrderTx.paymentPhaseKey(plan.paymentPhaseId), ReservationCardOrderTx.reusableUntil())

    @Test
    @Order(1)
    fun `migration runs twice, the entity validates, the app role can write, indexes as planned`() {
        val sql = ClassPathResource(MIGRATION).inputStream.bufferedReader().readText()
        // Twice, in a transaction like Flyway (it uses SET LOCAL): the migration must be re-runnable.
        jdbc.execute("BEGIN; $sql; COMMIT;")
        jdbc.execute("BEGIN; $sql; COMMIT;")
        jdbc.queryForObject("SELECT has_table_privilege('boat4you_app', 'reservation_card_order', 'SELECT, INSERT, UPDATE')", Boolean::class.java) shouldBe true
        jdbc.queryForObject("SELECT has_table_privilege('boat4you_app', 'reservation_card_order', 'DELETE')", Boolean::class.java) shouldBe false
        jdbc.queryForObject("SELECT has_sequence_privilege('boat4you_app', 'reservation_card_order_id_seq', 'USAGE')", Boolean::class.java) shouldBe true
        jdbc
            .queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'reservation_card_order' AND indexname LIKE 'idx_%'", String::class.java)
            .toSet() shouldBe
            setOf("idx_reservation_card_order_reservation_status", "idx_reservation_card_order_pending_created", "idx_reservation_card_order_flow_status")
        jdbc.queryForObject(
            "SELECT pg_get_indexdef('idx_reservation_card_order_pending_created'::regclass)",
            String::class.java,
        ) shouldBe "CREATE INDEX idx_reservation_card_order_pending_created ON public.reservation_card_order USING btree (created_at) WHERE ((status)::text = 'PENDING'::text)"

        // The phase columns: nullable VARCHARs, a partial NON-unique index, V1_56's unique index untouched.
        jdbc.queryForList(
            """
            SELECT column_name || ' ' || data_type || '(' || character_maximum_length || ') ' || is_nullable
            FROM information_schema.columns
            WHERE table_name = 'reservation_payment_phase' AND column_name IN ('payment_provider', 'provider_order_id', 'provider_payment_id')
            ORDER BY column_name
            """.trimIndent(),
            String::class.java,
        ) shouldContainExactly
            listOf("payment_provider character varying(16) YES", "provider_order_id character varying(64) YES", "provider_payment_id character varying(64) YES")
        jdbc.queryForObject(
            "SELECT pg_get_indexdef('idx_reservation_payment_phase_provider_order_id'::regclass)",
            String::class.java,
        ) shouldBe
            "CREATE INDEX idx_reservation_payment_phase_provider_order_id ON public.reservation_payment_phase USING btree (provider_order_id) WHERE (provider_order_id IS NOT NULL)"
        jdbc.queryForObject("SELECT indisunique FROM pg_index WHERE indexrelid = 'uniq_reservation_payment_phase_stripe_session_id'::regclass", Boolean::class.java) shouldBe true

        // ddl-auto=validate already passed when the context started; a JPA round trip on top.
        with(row(insert("ord-roundtrip").id!!)) {
            status shouldBe ReservationCardOrderStatus.PENDING
            provider shouldBe "REVOLUT"
            currency shouldBe "EUR"
            phaseIdList() shouldBe listOf(7L)
            baseAmount shouldBe BigDecimal("2677.60")
            surchargeAmount.compareTo(BigDecimal("134")) shouldBe 0
            promoteState.shouldBeNull()
            createdAt.shouldNotBeNull()
        }
    }

    @Test
    @Order(2)
    fun `phases - two phases may share one provider order id (pay full), a Stripe session id stays unique`() {
        fun phase(
            stripeSession: String?,
            orderId: String?,
        ) = jdbc.update(
            """
            INSERT INTO reservation_payment_phase (deadline, amount, paid_on, stripe_session_id, reservation_flow_id, created, entity_status,
                                                   payment_provider, provider_order_id, provider_payment_id)
            VALUES (current_date, 100, now(), ?, 900, now(), 'ACTIVE', 'REVOLUT', ?, 'pay-1')
            """.trimIndent(),
            stripeSession,
            orderId,
        )
        phase(null, "ord-payfull") shouldBe 1
        phase(null, "ord-payfull") shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM reservation_payment_phase WHERE provider_order_id = 'ord-payfull'", Long::class.java) shouldBe 2L
        phase("cs_test_unique", null) shouldBe 1
        shouldThrow<DataIntegrityViolationException> { phase("cs_test_unique", null) }
    }

    @Test
    @Order(3)
    fun `ledger - provider order id unique, CHECK constraints reject what the app must never write`() {
        fun ledger(
            orderId: String,
            provider: String = "REVOLUT",
            phaseIds: String = "7,8",
            amountMinor: Long = 386_160,
            base: String = "3677.60",
            currency: String = "EUR",
            status: String = "PENDING",
            promoteState: String? = null,
            reviewReason: String? = null,
        ) = jdbc.update(
            """
            INSERT INTO reservation_card_order (provider, provider_order_id, reservation_id, reservation_flow_id, phase_ids, base_amount,
                                                surcharge_amount, amount_minor, currency, reference, checkout_url, status, promote_state,
                                                review_reason, expires_at)
            VALUES (?, ?, 42, 900, ?, ?::numeric, 184, ?, ?, '1441002/2026', 'https://checkout.revolut.com/x', ?, ?, ?, now() + interval '1 hour')
            """.trimIndent(),
            provider,
            orderId,
            phaseIds,
            base,
            amountMinor,
            currency,
            status,
            promoteState,
            reviewReason,
        )

        ledger("ord-check-1") shouldBe 1
        ledger("ord-check-2", phaseIds = "", status = "NEEDS_REVIEW", promoteState = "PENDING", reviewReason = "r".repeat(1000)) shouldBe 1
        listOf("DONE", "FAILED").forEachIndexed { i, state -> ledger("ord-check-promote-$i", status = "COMPLETED", promoteState = state) shouldBe 1 }
        listOf("COMPLETED", "FAILED", "CANCELLED").forEachIndexed { i, status -> ledger("ord-check-status-$i", status = status) shouldBe 1 }
        val bad: List<() -> Int> =
            listOf(
                { ledger("ord-check-1") },
                { ledger("ord-bad-provider", provider = "STRIPE") },
                { ledger("ord-bad-phases-1", phaseIds = "7,,8") },
                { ledger("ord-bad-phases-2", phaseIds = "7;8") },
                { ledger("ord-bad-phases-3", phaseIds = " 7") },
                { ledger("ord-bad-phases-4", phaseIds = "7,") },
                { ledger("ord-bad-amount-1", amountMinor = 0) },
                { ledger("ord-bad-amount-2", amountMinor = -100) },
                { ledger("ord-bad-base", base = "-1") },
                { ledger("ord-bad-currency", currency = "USD") },
                { ledger("ord-bad-status", status = "PAID") },
                { ledger("ord-bad-promote", promoteState = "RUNNING") },
                { ledger("ord-bad-reason", reviewReason = "r".repeat(1001)) },
            )
        bad.forEach { shouldThrow<DataIntegrityViolationException> { it() } }
        CardOrderPromoteState.entries.map { it.name } shouldContainExactly listOf("PENDING", "DONE", "FAILED")
    }

    @Test
    @Order(4)
    fun `reuse finder - only PENDING, same flow, phase set, cents and payment phase, open for 10 more minutes`() {
        val base = plan(flowId = 901, phaseIds = "11,12", amountMinor = 500_000, paymentPhaseId = null)
        val hit = insert("ord-reuse-hit", base)
        reusable(base)!!.id shouldBe hit.id

        reusable(base.copy(phaseIds = "11")).shouldBeNull()
        reusable(base.copy(amountMinor = 500_100)).shouldBeNull()
        reusable(base.copy(paymentPhaseId = 11)).shouldBeNull()
        reusable(base.copy(reservationFlowId = 900)).shouldBeNull()

        jdbc.update("UPDATE reservation_card_order SET expires_at = now() + interval '9 minutes' WHERE id = ?", hit.id)
        reusable(base).shouldBeNull()
        jdbc.update("UPDATE reservation_card_order SET expires_at = now() + interval '11 minutes' WHERE id = ?", hit.id)
        reusable(base)!!.id shouldBe hit.id
        tx.closeIfPending(hit.id!!, ReservationCardOrderStatus.CANCELLED) shouldBe ReservationCardOrderStatus.CANCELLED
        reusable(base).shouldBeNull()

        // A pinned phase never matches the pay-full key (-1) and vice versa.
        val pinned = insert("ord-reuse-pinned", base.copy(phaseIds = "11", paymentPhaseId = 11))
        reusable(base.copy(phaseIds = "11", paymentPhaseId = 11))!!.id shouldBe pinned.id
        reusable(base.copy(phaseIds = "11", paymentPhaseId = null)).shouldBeNull()

        // Every PENDING order of the flow, oldest first; closed ones are gone.
        val older = insert("ord-reuse-other", base.copy(phaseIds = "12", amountMinor = 100_000))
        jdbc.update("UPDATE reservation_card_order SET created_at = now() - interval '1 hour' WHERE id = ?", older.id)
        orders.findPendingByReservationFlow(901).map { it.providerOrderId } shouldContainExactly listOf("ord-reuse-other", "ord-reuse-pinned")
    }

    @Test
    @Order(5)
    fun `insertPending - two identical concurrent checkouts end with ONE ledger row, the loser gets the winner's row`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(3) { round ->
                val samePlan = plan(flowId = 900, phaseIds = "2$round", amountMinor = 10_000L + round, paymentPhaseId = 20L + round)
                val start = CountDownLatch(1)
                val results =
                    listOf("a", "b").map { side ->
                        pool.submit<PendingInsert> {
                            start.await()
                            tx.insertPending(samePlan, "ord-race-$round-$side", "https://checkout.revolut.com/payment-link/$round-$side")
                        }
                    }
                start.countDown()
                val (first, second) = results.map { it.get(30, TimeUnit.SECONDS) }
                listOf(first.existing, second.existing) shouldContainExactlyInAnyOrder listOf(true, false)
                first.order.id shouldBe second.order.id
                jdbc.queryForObject(
                    "SELECT count(*) FROM reservation_card_order WHERE phase_ids = ? AND status = 'PENDING'",
                    Long::class.java,
                    "2$round",
                ) shouldBe 1L
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @Order(6)
    fun `claimCheck - 1 then 0 within 10 s, 1 again after, 0 once the order is no longer PENDING`() {
        val id = insert("ord-claim", plan(flowId = 900, phaseIds = "31", paymentPhaseId = 31)).id!!
        orders.claimCheck(id) shouldBe 1
        orders.claimCheck(id) shouldBe 0
        jdbc.update("UPDATE reservation_card_order SET last_checked_at = now() - interval '11 seconds' WHERE id = ?", id)
        orders.claimCheck(id) shouldBe 1
        tx.closeIfPending(id, ReservationCardOrderStatus.FAILED) shouldBe ReservationCardOrderStatus.FAILED
        jdbc.update("UPDATE reservation_card_order SET last_checked_at = NULL WHERE id = ?", id)
        orders.claimCheck(id) shouldBe 0
    }

    @Test
    @Order(7)
    fun `closeIfPending - only PENDING changes, the first closing status wins, completion statuses are refused`() {
        val id = insert("ord-close", plan(flowId = 900, phaseIds = "41", paymentPhaseId = 41)).id!!
        tx.closeIfPending(id, ReservationCardOrderStatus.CANCELLED) shouldBe ReservationCardOrderStatus.CANCELLED
        tx.closeIfPending(id, ReservationCardOrderStatus.FAILED) shouldBe ReservationCardOrderStatus.CANCELLED
        row(id).lastCheckedAt.shouldNotBeNull()
        tx.closeIfPending(999_999, ReservationCardOrderStatus.FAILED).shouldBeNull()
        shouldThrow<IllegalArgumentException> { tx.closeIfPending(id, ReservationCardOrderStatus.COMPLETED) }
        shouldThrow<IllegalArgumentException> { tx.closeIfPending(id, ReservationCardOrderStatus.NEEDS_REVIEW) }
    }

    @Test
    @Order(8)
    fun `reconcile candidates - PENDING, 5 min to 48 h old, not checked in 5 min, oldest first`() {
        jdbc.update("UPDATE reservation_card_order SET status = 'CANCELLED' WHERE status = 'PENDING'")
        var nextPhaseId = 60L
        fun pendingAged(
            orderId: String,
            age: String,
            checked: String? = null,
        ): Long {
            val phaseId = nextPhaseId++
            val id = insert(orderId, plan(flowId = 901, phaseIds = phaseId.toString(), paymentPhaseId = phaseId)).id!!
            jdbc.update("UPDATE reservation_card_order SET created_at = now() - ?::interval, last_checked_at = now() - ?::interval WHERE id = ?", age, checked, id)
            return id
        }
        pendingAged("ord-rc-fresh", "1 minute")
        val tenMinutes = pendingAged("ord-rc-10m", "10 minutes")
        val oneDay = pendingAged("ord-rc-1d", "1 day", checked = "6 minutes")
        pendingAged("ord-rc-checked", "1 hour", checked = "1 minute")
        pendingAged("ord-rc-3d", "3 days")
        val closed = pendingAged("ord-rc-closed", "1 hour")
        tx.closeIfPending(closed, ReservationCardOrderStatus.FAILED)
        jdbc.update("UPDATE reservation_card_order SET last_checked_at = NULL WHERE id = ?", closed)

        orders.findReconcileCandidateIds() shouldContainExactly listOf(oneDay, tenMinutes)
    }

    @Test
    @Order(9)
    fun `row lock - a second transaction gives up after lock_timeout (5 s) instead of waiting forever`() {
        val id = insert("ord-lock", plan(flowId = 900, phaseIds = "51", paymentPhaseId = 51)).id!!
        val template = TransactionTemplate(transactionManager)
        val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder =
                pool.submit {
                    template.executeWithoutResult {
                        orders.lockByProviderOrderId("ord-lock").shouldNotBeNull()
                        locked.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            locked.await(10, TimeUnit.SECONDS) shouldBe true
            val started = System.nanoTime()
            shouldThrow<PessimisticLockingFailureException> {
                template.executeWithoutResult {
                    orders.setLocalLockTimeout() shouldBe "5s"
                    orders.lockById(id)
                }
            }
            val waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
            waitedMs shouldBeGreaterThanOrEqual 4_500L
            waitedMs shouldBeLessThan 15_000L
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        // The advisory lock of a reservation flow works inside a transaction too.
        TransactionTemplate(transactionManager).execute { orders.lockKey(ReservationCardOrderTx.flowLockKey(900)) } shouldBe 1
    }
}
