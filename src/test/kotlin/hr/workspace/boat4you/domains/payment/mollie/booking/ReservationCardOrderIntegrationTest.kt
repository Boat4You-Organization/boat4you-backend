package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.payment.mollie.MolliePayment
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.BookingCheckoutMethod
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.CardOrderPromoteState
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.reservation.events.ReservationPaymentRecordedEvent
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * reservation_card_order + the three new reservation_payment_phase columns against a real PostgreSQL 18 (prod: cusma4).
 * The schema is the REAL Flyway chain (every migration, V9_123 included, with the same empty-database shims as
 * SocialHibernateValidateTest), then Hibernate boots ALL entities with `ddl-auto=validate` like prod — so both
 * ReservationCardOrder and ReservationPaymentPhase (payment_provider, provider_order_id, provider_payment_id) are
 * validated against the migration. V9_123 is then re-run twice in a transaction like Flyway (idempotent). On top: the
 * CHECK / UNIQUE rules, the repository queries (reuse before create, pending per flow, claimCheck, reconcile
 * candidates, row locks with lock_timeout), ReservationCardOrderTx.insertPending / closeIfPending under real locks and
 * applyCompleted against real phase rows. Foreign-key targets (reservation, reservation_flow) are minimal stub rows
 * inserted with session_replication_role=replica.
 */
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ReservationCardOrderTx::class, ReservationCardOrderIntegrationTest.PaymentEvents::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReservationCardOrderIntegrationTest {
    companion object {
        private const val MIGRATION = "db/migration/V9_123__reservation_card_order.sql"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
                withDatabaseName("boat4you_db")
                withCopyFileToContainer(MountableFile.forClasspathResource("init/00_roles.sql"), "/docker-entrypoint-initdb.d/00_roles.sql")
            }

        private val chainMigrated by lazy {
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .callbacks(EmptyDatabaseShims)
                .load()
                .migrate()
            true
        }

        /** Runs after the container started and before the Spring context connects: the schema is in place when Hibernate validates. */
        @DynamicPropertySource
        @JvmStatic
        fun migrateWholeChain(registry: DynamicPropertyRegistry) {
            check(chainMigrated)
        }
    }

    /** The shims an empty database needs (see SocialHibernateValidateTest): V9_18 is a prod data fix, V9_50 reads a repeatable view. */
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

        override fun getCallbackName() = "card-order-test-empty-db-shims"
    }

    @SpringBootConfiguration
    @EntityScan("hr.workspace.boat4you")
    @EnableJpaRepositories(
        basePackageClasses = [ReservationCardOrderRepository::class, ReservationPaymentPhaseRepository::class, ReservationRepository::class],
        includeFilters = [
            ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                classes = [ReservationCardOrderRepository::class, ReservationPaymentPhaseRepository::class, ReservationRepository::class],
            ),
        ],
    )
    class AllEntitiesTwoRepositories

    /** Records the events ReservationCardOrderTx publishes (reservation ids). */
    @Component
    class PaymentEvents {
        val recorded = CopyOnWriteArrayList<Long>()

        @EventListener
        fun on(event: ReservationPaymentRecordedEvent) {
            recorded += event.reservationId
        }
    }

    @Autowired
    private lateinit var events: PaymentEvents

    @Autowired
    private lateinit var phases: ReservationPaymentPhaseRepository

    @Autowired
    private lateinit var orders: ReservationCardOrderRepository

    @Autowired
    private lateinit var tx: ReservationCardOrderTx

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    /** Minimal stand-in rows for the foreign-key targets (all NOT NULL columns without a default filled by type; FKs skipped). */
    private fun stub(
        table: String,
        id: Long,
        overrides: Map<String, String> = emptyMap(),
    ) {
        jdbc.execute(
            ConnectionCallback { con ->
                con.createStatement().use { it.execute("SET session_replication_role = replica") }
                try {
                    val columns = mutableListOf<String>()
                    val values = mutableListOf<String>()
                    con
                        .prepareStatement(
                            "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? " +
                                "AND is_nullable = 'NO' AND column_default IS NULL AND is_generated = 'NEVER' AND column_name <> 'id'",
                        ).use { ps ->
                            ps.setString(1, table)
                            ps.executeQuery().use { rs ->
                                while (rs.next()) {
                                    columns += "\"" + rs.getString(1) + "\""
                                    values +=
                                        overrides[rs.getString(1)] ?: when (rs.getString(2)) {
                                            "bigint", "integer", "smallint", "numeric", "double precision", "real" -> "0"
                                            "boolean" -> "false"
                                            "date" -> "current_date"
                                            "uuid" -> "gen_random_uuid()"
                                            "jsonb", "json" -> "'{}'"
                                            else -> if (rs.getString(2).startsWith("timestamp")) "now()" else "'x'"
                                        }
                                }
                            }
                        }
                    val cols = (listOf("id") + columns).joinToString(", ")
                    val vals = (listOf(id.toString()) + values).joinToString(", ")
                    con.createStatement().use { it.execute("INSERT INTO $table ($cols) VALUES ($vals) ON CONFLICT DO NOTHING") }
                } finally {
                    con.createStatement().use { it.execute("SET session_replication_role = origin") }
                }
            },
        )
    }

    @BeforeEach
    fun seedForeignKeyTargets() {
        listOf(42L, 43L).forEach { stub("reservation", it, mapOf("status" to "'RESERVED'", "sys_status" to "'RESERVATION'")) }
        jdbc.update("UPDATE reservation SET sys_status = 'RESERVATION' WHERE id IN (42, 43)")
        listOf(900L, 901L).forEach { stub("reservation_flow", it) }
        events.recorded.clear()
    }

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
        checkoutToken = UUID.randomUUID(),
        expiresAfter = expiresAfter,
        expiresAt = Instant.now().plus(expiresAfter),
    )

    private fun insert(
        orderId: String,
        plan: BookingOrderPlan = plan(),
    ): ReservationCardOrder {
        val inserted = tx.insertPending(plan.copy(checkoutToken = UUID.randomUUID()), orderId, "https://www.mollie.com/checkout/select-method/$orderId")
        inserted.existing shouldBe false
        return inserted.order
    }

    private fun row(id: Long): ReservationCardOrder = orders.findById(id).orElseThrow()

    private fun reusable(plan: BookingOrderPlan) =
        orders.findReusable(
            plan.reservationFlowId,
            plan.phaseIds,
            plan.amountMinor,
            ReservationCardOrderTx.paymentPhaseKey(plan.paymentPhaseId),
            ReservationCardOrderTx.reusableUntil(),
        )

    @Test
    @Order(1)
    fun `migration runs twice, the entity validates, the app role can write, indexes as planned`() {
        val sql = ClassPathResource(MIGRATION).inputStream.bufferedReader().readText()
        // Twice, in a transaction like Flyway (it uses SET LOCAL): the migration must be re-runnable.
        jdbc.execute("BEGIN; $sql; COMMIT;")
        jdbc.execute("BEGIN; $sql; COMMIT;")
        jdbc.queryForObject("SELECT has_table_privilege('boat4you_app', 'reservation_card_order', 'SELECT, INSERT, UPDATE')", Boolean::class.java) shouldBe true
        jdbc.queryForObject("SELECT has_sequence_privilege('boat4you_app', 'reservation_card_order_id_seq', 'USAGE')", Boolean::class.java) shouldBe true
        jdbc
            .queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'reservation_card_order' AND indexname LIKE 'idx_%'", String::class.java)
            .toSet() shouldBe
            setOf("idx_reservation_card_order_reservation_status", "idx_reservation_card_order_pending_created", "idx_reservation_card_order_flow_status")
        jdbc.queryForObject(
            "SELECT pg_get_indexdef('idx_reservation_card_order_pending_created'::regclass)",
            String::class.java,
        ) shouldBe
            "CREATE INDEX idx_reservation_card_order_pending_created ON public.reservation_card_order USING btree (created_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUPERSEDED'::character varying])::text[]))"

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
        with(row(insert("tr_roundtrip").id!!)) {
            status shouldBe ReservationCardOrderStatus.PENDING
            provider shouldBe "MOLLIE"
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
            VALUES (current_date, 100, now(), ?, 900, now(), 'ACTIVE', 'MOLLIE', ?, 'tr_payfull')
            """.trimIndent(),
            stripeSession,
            orderId,
        )
        phase(null, "tr_payfull") shouldBe 1
        phase(null, "tr_payfull") shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM reservation_payment_phase WHERE provider_order_id = 'tr_payfull'", Long::class.java) shouldBe 2L
        phase("cs_test_unique", null) shouldBe 1
        shouldThrow<DataIntegrityViolationException> { phase("cs_test_unique", null) }
    }

    @Test
    @Order(3)
    fun `ledger - provider order id unique, CHECK constraints reject what the app must never write`() {
        fun ledger(
            orderId: String,
            provider: String = "MOLLIE",
            phaseIds: String = "7,8",
            amountMinor: Long = 386_160,
            base: String = "3677.60",
            currency: String = "EUR",
            status: String = "PENDING",
            promoteState: String? = null,
            reviewReason: String? = null,
        ) = jdbc.update(
            """
            INSERT INTO reservation_card_order (provider, provider_order_id, checkout_token, reservation_id, reservation_flow_id, phase_ids, base_amount,
                                                surcharge_amount, amount_minor, currency, reference, checkout_url, status, promote_state,
                                                review_reason, expires_at)
            VALUES (?, ?, gen_random_uuid(), 42, 900, ?, ?::numeric, 184, ?, ?, '1441002/2026', 'https://www.mollie.com/checkout/select-method/x', ?, ?, ?, now() + interval '1 hour')
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

        ledger("tr_check-1") shouldBe 1
        ledger("tr_check-2", phaseIds = "", status = "NEEDS_REVIEW", promoteState = "PENDING", reviewReason = "r".repeat(1000)) shouldBe 1
        listOf("DONE", "FAILED").forEachIndexed { i, state -> ledger("tr_check-promote-$i", status = "COMPLETED", promoteState = state) shouldBe 1 }
        listOf("COMPLETED", "FAILED", "CANCELLED").forEachIndexed { i, status -> ledger("tr_check-status-$i", status = status) shouldBe 1 }
        val bad: List<() -> Int> =
            listOf(
                { ledger("tr_check-1") },
                { ledger("tr_bad-provider", provider = "STRIPE") },
                { ledger("tr_bad-provider-2", provider = "REVOLUT") },
                { ledger("tr_bad-phases-1", phaseIds = "7,,8") },
                { ledger("tr_bad-phases-2", phaseIds = "7;8") },
                { ledger("tr_bad-phases-3", phaseIds = " 7") },
                { ledger("tr_bad-phases-4", phaseIds = "7,") },
                { ledger("tr_bad-amount-1", amountMinor = 0) },
                { ledger("tr_bad-amount-2", amountMinor = -100) },
                { ledger("tr_bad-base", base = "-1") },
                { ledger("tr_bad-currency", currency = "USD") },
                { ledger("tr_bad-status", status = "PAID") },
                { ledger("tr_bad-promote", promoteState = "RUNNING") },
                { ledger("tr_bad-reason", reviewReason = "r".repeat(1001)) },
            )
        bad.forEach { shouldThrow<DataIntegrityViolationException> { it() } }
        CardOrderPromoteState.entries.map { it.name } shouldContainExactly listOf("PENDING", "DONE", "FAILED")
    }

    @Test
    @Order(4)
    fun `reuse finder - only PENDING, same flow, phase set, cents and payment phase, open for 10 more minutes`() {
        val base = plan(flowId = 901, phaseIds = "11,12", amountMinor = 500_000, paymentPhaseId = null)
        val hit = insert("tr_reuse-hit", base)
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
        val pinned = insert("tr_reuse-pinned", base.copy(phaseIds = "11", paymentPhaseId = 11))
        reusable(base.copy(phaseIds = "11", paymentPhaseId = 11))!!.id shouldBe pinned.id
        reusable(base.copy(phaseIds = "11", paymentPhaseId = null)).shouldBeNull()

        // Every PENDING order of the flow, oldest first; closed ones are gone.
        val older = insert("tr_reuse-other", base.copy(phaseIds = "12", amountMinor = 100_000))
        jdbc.update("UPDATE reservation_card_order SET created_at = now() - interval '1 hour' WHERE id = ?", older.id)
        orders.findPendingByReservationFlow(901).map { it.providerOrderId } shouldContainExactly listOf("tr_reuse-other", "tr_reuse-pinned")
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
                            tx.insertPending(samePlan.copy(checkoutToken = UUID.randomUUID()), "tr_race-$round-$side", "https://www.mollie.com/checkout/select-method/$round-$side")
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
        val id = insert("tr_claim", plan(flowId = 900, phaseIds = "31", paymentPhaseId = 31)).id!!
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
        val id = insert("tr_close", plan(flowId = 900, phaseIds = "41", paymentPhaseId = 41)).id!!
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
        pendingAged("tr_rc-fresh", "1 minute")
        val tenMinutes = pendingAged("tr_rc-10m", "10 minutes")
        val oneDay = pendingAged("tr_rc-1d", "1 day", checked = "6 minutes")
        pendingAged("tr_rc-checked", "1 hour", checked = "1 minute")
        pendingAged("tr_rc-3d", "3 days")
        val closed = pendingAged("tr_rc-closed", "1 hour")
        tx.closeIfPending(closed, ReservationCardOrderStatus.FAILED)
        jdbc.update("UPDATE reservation_card_order SET last_checked_at = NULL WHERE id = ?", closed)

        orders.findReconcileCandidateIds() shouldContainExactly listOf(oneDay, tenMinutes)
    }

    @Test
    @Order(9)
    fun `row lock - a second transaction gives up after lock_timeout (5 s) instead of waiting forever`() {
        val id = insert("tr_lock", plan(flowId = 900, phaseIds = "51", paymentPhaseId = 51)).id!!
        val template = TransactionTemplate(transactionManager)
        val pool = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder =
                pool.submit {
                    template.executeWithoutResult {
                        orders.lockByProviderOrderId("tr_lock").shouldNotBeNull()
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

    @Test
    @Order(10)
    fun `insertPending - the same Mollie payment id twice gives ONE row, the second call returns it as existing`() {
        val first =
            tx.insertPending(
                plan(flowId = 900, phaseIds = "71", paymentPhaseId = 71).copy(checkoutToken = UUID.randomUUID()),
                "tr_same-id",
                "https://www.mollie.com/checkout/select-method/same-1",
            )
        first.existing shouldBe false
        // An Idempotency-Key replay: same Mollie id, even for a plan that would not be reusable on its own.
        val second =
            tx.insertPending(
                plan(flowId = 901, phaseIds = "72", amountMinor = 99_900, paymentPhaseId = 72).copy(checkoutToken = UUID.randomUUID()),
                "tr_same-id",
                "https://www.mollie.com/checkout/select-method/same-2",
            )
        second.existing shouldBe true
        second.order.id shouldBe first.order.id
        second.order.checkoutUrl shouldBe "https://www.mollie.com/checkout/select-method/same-1"
        jdbc.queryForObject("SELECT count(*) FROM reservation_card_order WHERE provider_order_id = 'tr_same-id'", Long::class.java) shouldBe 1L
    }

    private fun newPhase(
        flowId: Long,
        paid: Boolean = false,
    ): Long =
        jdbc.queryForObject(
            """
            INSERT INTO reservation_payment_phase (deadline, amount, paid_on, reservation_flow_id, created, entity_status)
            VALUES (current_date, 1000, CASE WHEN ? THEN now() ELSE NULL END, ?, now(), 'ACTIVE') RETURNING id
            """.trimIndent(),
            Long::class.java,
            paid,
            flowId,
        )!!

    /** A PENDING ledger row over [phaseIds] (flow 900, reservation 42) with the amount of one 2,000 EUR charge. */
    private fun pendingOver(
        orderId: String,
        phaseIds: List<Long>,
        paymentPhaseId: Long?,
        amountMinor: Long = 200_000,
    ): ReservationCardOrder =
        insert(
            orderId,
            plan(
                flowId = 900,
                phaseIds = ReservationCardOrder.phaseIdsOf(phaseIds),
                amountMinor = amountMinor,
                paymentPhaseId = paymentPhaseId,
            ),
        )

    private fun paidPayment(
        order: ReservationCardOrder,
        status: String = "paid",
        amountMinor: Long = order.amountMinor,
        currency: String = "EUR",
    ) = MolliePayment(
        id = order.providerOrderId,
        status = status,
        amount = MollieAmount(currency, MollieAmount.eur(amountMinor).value),
        metadata =
            mapOf(
                ReservationCardOrderTx.FLOW_METADATA_KEY to order.reservationFlowId.toString(),
                ReservationCardOrderTx.PHASES_METADATA_KEY to order.phaseIds,
            ),
    )

    private fun phaseRow(id: Long) = phases.findById(id).orElseThrow()

    @Test
    @Order(11)
    fun `applyCompleted - paid and matching - row COMPLETED, every phase gets paidOn + MOLLIE ids, promote only without a pinned phase`() {
        // Pay full (no pinned phase): two phases, promote to booking afterwards.
        val phaseA = newPhase(900)
        val phaseB = newPhase(900)
        val full = pendingOver("tr_done-full", listOf(phaseA, phaseB), paymentPhaseId = null)
        val result = tx.applyCompleted(full.id!!, paidPayment(full), "mollie-webhook")
        result.completedNow shouldBe true
        result.promote shouldBe true
        result.alert.shouldBeNull()
        result.reservationId shouldBe 42L
        with(row(full.id!!)) {
            status shouldBe ReservationCardOrderStatus.COMPLETED
            completedAt.shouldNotBeNull()
            providerPaymentId shouldBe "tr_done-full"
            promoteState shouldBe CardOrderPromoteState.PENDING
            lastWebhookEvent shouldBe "mollie-webhook"
        }
        listOf(phaseA, phaseB).forEach {
            with(phaseRow(it)) {
                paidOn.shouldNotBeNull()
                paymentProvider shouldBe "MOLLIE"
                providerOrderId shouldBe "tr_done-full"
                providerPaymentId shouldBe "tr_done-full"
            }
        }
        events.recorded shouldContainExactly listOf(42L)

        // One pinned phase: no promote.
        val pinnedPhase = newPhase(900)
        val pinned = pendingOver("tr_done-pinned", listOf(pinnedPhase), paymentPhaseId = pinnedPhase)
        val pinnedResult = tx.applyCompleted(pinned.id!!, paidPayment(pinned), null)
        pinnedResult.completedNow shouldBe true
        pinnedResult.promote shouldBe false
        row(pinned.id!!).promoteState.shouldBeNull()
        phaseRow(pinnedPhase).paidOn.shouldNotBeNull()
    }

    @Test
    @Order(12)
    fun `applyCompleted - a second call is a no-op (one event, phases stamped once)`() {
        val phase = newPhase(900)
        val order = pendingOver("tr_twice", listOf(phase), paymentPhaseId = phase)
        tx.applyCompleted(order.id!!, paidPayment(order), "mollie-webhook").completedNow shouldBe true
        val paidOn = phaseRow(phase).paidOn
        val completedAt = row(order.id!!).completedAt

        val again = tx.applyCompleted(order.id!!, paidPayment(order), "mollie-webhook")
        again.completedNow shouldBe false
        again.promote shouldBe false
        again.alert.shouldBeNull()
        phaseRow(phase).paidOn shouldBe paidOn
        row(order.id!!).completedAt shouldBe completedAt
        events.recorded shouldContainExactly listOf(42L)
    }

    @Test
    @Order(13)
    fun `applyCompleted - a phase that is already paid is NEEDS_REVIEW with DOUBLE PAYMENT, no phase is touched`() {
        val free = newPhase(900)
        val alreadyPaid = newPhase(900, paid = true)
        val order = pendingOver("tr_double", listOf(free, alreadyPaid), paymentPhaseId = null)
        val result = tx.applyCompleted(order.id!!, paidPayment(order), "mollie-webhook")
        result.completedNow shouldBe false
        result.promote shouldBe false
        result.alert.shouldNotBeNull() shouldContain "DOUBLE PAYMENT"
        with(row(order.id!!)) {
            status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
            reviewReason.shouldNotBeNull() shouldContain "DOUBLE PAYMENT"
            completedAt.shouldBeNull()
        }
        phaseRow(free).paidOn.shouldBeNull()
        phaseRow(free).paymentProvider.shouldBeNull()
        phaseRow(alreadyPaid).paymentProvider.shouldBeNull()
        events.recorded.size shouldBe 0
        // NEEDS_REVIEW is final for the automatic paths.
        tx.applyCompleted(order.id!!, paidPayment(order), null) shouldBe CompletionResult.NOTHING
    }

    @Test
    @Order(14)
    fun `applyCompleted - amount, currency or metadata not matching is NEEDS_REVIEW and the phases stay unpaid`() {
        fun review(
            orderId: String,
            payment: (ReservationCardOrder) -> MolliePayment,
            reason: String,
        ) {
            val phase = newPhase(900)
            val order = pendingOver(orderId, listOf(phase), paymentPhaseId = phase)
            val result = tx.applyCompleted(order.id!!, payment(order), "mollie-webhook")
            result.completedNow shouldBe false
            result.alert.shouldNotBeNull() shouldContain reason
            row(order.id!!).status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
            phaseRow(phase).paidOn.shouldBeNull()
        }
        review("tr_bad-amount", { paidPayment(it, amountMinor = it.amountMinor - 100) }, "amount")
        review("tr_bad-currency", { paidPayment(it, currency = "USD") }, "currency USD")
        review("tr_bad-flow", {
            paidPayment(it).copy(metadata = mapOf(ReservationCardOrderTx.FLOW_METADATA_KEY to "901", ReservationCardOrderTx.PHASES_METADATA_KEY to it.phaseIds))
        }, "metadata")
        review("tr_bad-phases", {
            paidPayment(it).copy(metadata = mapOf(ReservationCardOrderTx.FLOW_METADATA_KEY to "900", ReservationCardOrderTx.PHASES_METADATA_KEY to "1"))
        }, "metadata")
        events.recorded.size shouldBe 0
    }

    @Test
    @Order(15)
    fun `applyCompleted - a CANCELLED (superseded) row that Mollie reports paid is still recorded, an unpaid status is not`() {
        val phase = newPhase(900)
        val order = pendingOver("tr_superseded", listOf(phase), paymentPhaseId = phase)
        tx.closeIfPending(order.id!!, ReservationCardOrderStatus.CANCELLED) shouldBe ReservationCardOrderStatus.CANCELLED

        // Not paid (yet): nothing changes.
        tx.applyCompleted(order.id!!, paidPayment(order, status = "open"), null) shouldBe CompletionResult.NOTHING
        row(order.id!!).status shouldBe ReservationCardOrderStatus.CANCELLED

        val result = tx.applyCompleted(order.id!!, paidPayment(order), "mollie-webhook")
        result.completedNow shouldBe true
        row(order.id!!).status shouldBe ReservationCardOrderStatus.COMPLETED
        phaseRow(phase).paidOn.shouldNotBeNull()
        phaseRow(phase).paymentProvider shouldBe "MOLLIE"
        events.recorded shouldContainExactly listOf(42L)
        tx.applyCompleted(999_999, paidPayment(order), null) shouldBe CompletionResult.NOTHING
    }

    @Test
    @Order(16)
    fun `SUPERSEDED - still claimable and reconciled, closed by Mollie, and a payment that arrives later is recorded`() {
        jdbc.update("UPDATE reservation_card_order SET status = 'CANCELLED' WHERE status IN ('PENDING', 'SUPERSEDED')")
        val phase = newPhase(901)
        val order = insert("tr_superseded-paid", plan(flowId = 901, phaseIds = phase.toString(), paymentPhaseId = phase, amountMinor = 123_400))
        tx.markSuperseded(order.id!!) shouldBe ReservationCardOrderStatus.SUPERSEDED
        tx.markSuperseded(999_999).shouldBeNull()
        row(order.id!!).status shouldBe ReservationCardOrderStatus.SUPERSEDED

        // Readable until Mollie decides: claimCheck works, the reconcile job picks it up, a newer pending list ignores it.
        // (markSuperseded stamps last_checked_at, so the 10 s window is open first)
        orders.claimCheck(order.id!!) shouldBe 0
        jdbc.update("UPDATE reservation_card_order SET last_checked_at = NULL WHERE id = ?", order.id)
        orders.claimCheck(order.id!!) shouldBe 1
        orders.claimCheck(order.id!!) shouldBe 0
        jdbc.update("UPDATE reservation_card_order SET created_at = now() - interval '1 hour', last_checked_at = NULL WHERE id = ?", order.id)
        orders.findReconcileCandidateIds() shouldContainExactly listOf(order.id!!)
        orders.findPendingByReservationFlow(901).map { it.id } shouldBe emptyList()
        jdbc.update("UPDATE reservation_card_order SET created_at = now() - interval '49 hours' WHERE id = ?", order.id)
        orders.findStaleOpenIds() shouldContainExactly listOf(order.id!!)

        // Mollie reports it paid after all: recorded like a PENDING one.
        val result = tx.applyCompleted(order.id!!, paidPayment(order), null)
        result.completedNow shouldBe true
        row(order.id!!).status shouldBe ReservationCardOrderStatus.COMPLETED
        phaseRow(phase).paidOn.shouldNotBeNull()
        phaseRow(phase).paymentProvider shouldBe "MOLLIE"
        orders.claimCheck(order.id!!) shouldBe 0

        // Mollie closing a SUPERSEDED payment ends it; markStaleOpen only touches open rows.
        val other = insert("tr_superseded-failed", plan(flowId = 901, phaseIds = "81", paymentPhaseId = 81, amountMinor = 55_500))
        tx.markSuperseded(other.id!!)
        tx.closeIfPending(other.id!!, ReservationCardOrderStatus.FAILED) shouldBe ReservationCardOrderStatus.FAILED
        tx.markStaleOpen(other.id!!) shouldBe false
        val stale = insert("tr_stale-open", plan(flowId = 901, phaseIds = "82", paymentPhaseId = 82, amountMinor = 66_600))
        tx.markSuperseded(stale.id!!)
        tx.markStaleOpen(stale.id!!) shouldBe true
        with(row(stale.id!!)) {
            status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
            reviewReason.shouldNotBeNull() shouldContain "48 hours"
        }
        tx.markStaleOpen(stale.id!!) shouldBe false
    }

    @Test
    @Order(17)
    fun `applyCompleted - a cancelled or deleted reservation is NEEDS_REVIEW and the phases stay unpaid`() {
        val cancelledPhase = newPhase(901)
        val cancelled = insert("tr_res-cancelled", plan(flowId = 901, phaseIds = cancelledPhase.toString(), paymentPhaseId = cancelledPhase, amountMinor = 71_100))
        jdbc.update("UPDATE reservation SET sys_status = 'CANCELLED' WHERE id = 43")
        try {
            val result = tx.applyCompleted(cancelled.id!!, paidPayment(cancelled), "mollie-webhook")
            result.completedNow shouldBe false
            result.alert.shouldNotBeNull() shouldContain "cancelled or deleted"
            row(cancelled.id!!).status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
            phaseRow(cancelledPhase).paidOn.shouldBeNull()
        } finally {
            jdbc.update("UPDATE reservation SET sys_status = 'RESERVATION' WHERE id = 43")
        }

        val deletedPhase = newPhase(901)
        val deleted = insert("tr_res-deleted", plan(flowId = 901, phaseIds = deletedPhase.toString(), paymentPhaseId = deletedPhase, amountMinor = 72_200))
        jdbc.update("UPDATE reservation_card_order SET reservation_id = NULL WHERE id = ?", deleted.id)
        tx.applyCompleted(deleted.id!!, paidPayment(deleted), null).alert.shouldNotBeNull() shouldContain "cancelled or deleted"
        row(deleted.id!!).status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
        phaseRow(deletedPhase).paidOn.shouldBeNull()
        events.recorded.size shouldBe 0
    }

    @Test
    @Order(18)
    fun `applyCompleted - legacy pay-full without phase rows - the second payment of the same flow is a DOUBLE PAYMENT`() {
        jdbc.update("UPDATE reservation_card_order SET status = 'CANCELLED' WHERE reservation_flow_id = 901 AND status IN ('PENDING', 'SUPERSEDED')")
        jdbc.update("UPDATE reservation_card_order SET status = 'CANCELLED' WHERE reservation_flow_id = 901 AND status = 'COMPLETED'")
        val first = insert("tr_legacy-1", plan(flowId = 901, phaseIds = "", amountMinor = 81_100, paymentPhaseId = null))
        tx.applyCompleted(first.id!!, paidPayment(first), "mollie-webhook").let {
            it.completedNow shouldBe true
            it.promote shouldBe true
        }
        val second = insert("tr_legacy-2", plan(flowId = 901, phaseIds = "", amountMinor = 82_200, paymentPhaseId = null))
        val result = tx.applyCompleted(second.id!!, paidPayment(second), "mollie-webhook")
        result.completedNow shouldBe false
        result.alert.shouldNotBeNull() shouldContain "DOUBLE PAYMENT"
        row(second.id!!).status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
        row(first.id!!).status shouldBe ReservationCardOrderStatus.COMPLETED
        orders.countOtherCompleted(901, second.id!!) shouldBe 1L
        orders.findStuckPromoteIds().contains(first.id!!) shouldBe false
        jdbc.update("UPDATE reservation_card_order SET completed_at = now() - interval '31 minutes' WHERE id = ?", first.id)
        orders.findStuckPromoteIds().contains(first.id!!) shouldBe true
        tx.markPromote(first.id!!, CardOrderPromoteState.DONE)
        orders.findStuckPromoteIds().contains(first.id!!) shouldBe false
    }

    @Test
    @Order(19)
    fun `applyMoneyBack - refund and chargeback totals only grow, each increase is reported once`() {
        val phase = newPhase(900)
        val order = pendingOver("tr_moneyback", listOf(phase), paymentPhaseId = phase, amountMinor = 150_000)
        tx.applyCompleted(order.id!!, paidPayment(order), null).completedNow shouldBe true

        fun withTotals(
            refunded: Long?,
            chargedBack: Long?,
        ) = paidPayment(order).copy(
            amountRefunded = refunded?.let { MollieAmount.eur(it) },
            amountChargedBack = chargedBack?.let { MollieAmount.eur(it) },
        )

        tx.applyMoneyBack(order.id!!, withTotals(null, null)).shouldBeNull()
        tx.applyMoneyBack(order.id!!, withTotals(5_000, null)).shouldNotBeNull() shouldContain "refunded 5000"
        row(order.id!!).refundedAmountMinor shouldBe 5_000L
        tx.applyMoneyBack(order.id!!, withTotals(5_000, null)).shouldBeNull()
        // A lower total never shrinks the stored one.
        tx.applyMoneyBack(order.id!!, withTotals(2_000, null)).shouldBeNull()
        row(order.id!!).refundedAmountMinor shouldBe 5_000L

        val chargeback = tx.applyMoneyBack(order.id!!, withTotals(5_000, 30_000))
        chargeback.shouldNotBeNull() shouldContain "CHARGEBACK 30000"
        chargeback shouldNotContain "refunded"
        with(row(order.id!!)) {
            refundedAmountMinor shouldBe 5_000L
            chargedBackAmountMinor shouldBe 30_000L
            status shouldBe ReservationCardOrderStatus.COMPLETED
        }
        tx.applyMoneyBack(order.id!!, withTotals(5_000, 30_000)).shouldBeNull()
        // Both grow at once: one reason, clamped to the charge.
        tx.applyMoneyBack(order.id!!, withTotals(900_000, 40_000)).shouldNotBeNull().let {
            it shouldContain "refunded 150000"
            it shouldContain "CHARGEBACK 40000"
        }
        row(order.id!!).refundedAmountMinor shouldBe 150_000L
        tx.applyMoneyBack(999_999, withTotals(1, 1)).shouldBeNull()
    }

    @Test
    @Order(20)
    fun `admin list - client and booking number joined, filters by status, reservation and q, total of what was kept`() {
        stub("reservation", 77, mapOf("status" to "'RESERVED'", "sys_status" to "'RESERVATION'"))
        stub("reservation_flow", 977)
        jdbc.update("UPDATE reservation SET reservation_number = '9977001/2026' WHERE id = 77")
        jdbc.update("UPDATE reservation_flow SET name = 'Ana', surname = 'Adminlist', email = 'ana.adminlist@example.com', phone = '+385911234567' WHERE id = 977")
        val all = ReservationCardOrderStatus.entries

        fun order(
            orderId: String,
            phase: Long,
            amountMinor: Long,
        ) = insert(orderId, plan(flowId = 977, phaseIds = phase.toString(), amountMinor = amountMinor, paymentPhaseId = phase).copy(reservationId = 77, reference = "9977001/2026"))
        val paid = order("tr_adminPaid", 71, 100_000)
        val open = order("tr_adminOpen", 72, 200_000)
        val failed = order("tr_adminFailed", 73, 300_000)
        // A payment whose reservation was purged (ON DELETE SET NULL): it stays in the list, without a booking number.
        val orphan = order("tr_adminOrphan", 74, 400_000)
        jdbc.update(
            "UPDATE reservation_card_order SET status = 'COMPLETED', completed_at = now(), refunded_amount_minor = 5000, charged_back_amount_minor = 1000 WHERE id = ?",
            paid.id,
        )
        jdbc.update("UPDATE reservation_card_order SET status = 'FAILED' WHERE id = ?", failed.id)
        jdbc.update("UPDATE reservation_card_order SET status = 'COMPLETED', reservation_id = NULL WHERE id = ?", orphan.id)
        val firstPage = PageRequest.of(0, 10)

        val ofBooking = orders.searchForAdmin(all, 77, null, firstPage)
        ofBooking.totalElements shouldBe 3L
        ofBooking.content.map { (it[0] as ReservationCardOrder).providerOrderId } shouldBe listOf("tr_adminFailed", "tr_adminOpen", "tr_adminPaid")
        with(ofBooking.content.first()) {
            this[1] shouldBe "Ana"
            this[2] shouldBe "Adminlist"
            this[3] shouldBe "ana.adminlist@example.com"
            this[4] shouldBe "+385911234567"
            this[5] shouldBe "9977001/2026"
        }

        // q: client name (both words), e-mail, booking number, Mollie payment id - case-insensitive; no reservation filter.
        orders.searchForAdmin(all, -1, "ana ADMINLIST", firstPage).totalElements shouldBe 4L
        orders.searchForAdmin(all, -1, "adminlist@example", firstPage).totalElements shouldBe 4L
        orders.searchForAdmin(all, -1, "9977001", firstPage).totalElements shouldBe 4L
        orders.searchForAdmin(all, -1, "TR_ADMINOPEN", firstPage).content.map { (it[0] as ReservationCardOrder).id } shouldBe listOf(open.id)
        orders.searchForAdmin(all, -1, "no-such-client", firstPage).totalElements shouldBe 0L

        // Status groups of the admin tabs + paging.
        val notPaid = listOf(ReservationCardOrderStatus.FAILED, ReservationCardOrderStatus.CANCELLED)
        orders.searchForAdmin(notPaid, -1, "adminlist", firstPage).content.map { (it[0] as ReservationCardOrder).id } shouldBe listOf(failed.id)
        with(orders.searchForAdmin(all, -1, "adminlist", PageRequest.of(1, 3))) {
            totalElements shouldBe 4L
            totalPages shouldBe 2
            content.map { (it[0] as ReservationCardOrder).id } shouldBe listOf(paid.id)
        }
        with(orders.searchForAdmin(all, -1, "tr_adminOrphan", firstPage).content.single()) {
            (this[0] as ReservationCardOrder).reservationId.shouldBeNull()
            this[5].shouldBeNull()
            this[1] shouldBe "Ana"
        }

        // Kept = COMPLETED only, charged - refunded - charged back; the filters apply to the total too.
        orders.sumReceivedForAdmin(all, 77, null, ReservationCardOrderStatus.COMPLETED) shouldBe 94_000L
        orders.sumReceivedForAdmin(all, -1, "adminlist", ReservationCardOrderStatus.COMPLETED) shouldBe 494_000L
        orders.sumReceivedForAdmin(notPaid, -1, "adminlist", ReservationCardOrderStatus.COMPLETED) shouldBe 0L

        with(orders.findAdminRowById(paid.id!!).single()) {
            (this[0] as ReservationCardOrder).refundedAmountMinor shouldBe 5_000L
            this[5] shouldBe "9977001/2026"
        }
        orders.findAdminRowById(999_999).shouldBeEmpty()
    }

    @Test
    @Order(21)
    fun `method (V9_124) - a card checkout is never reused for Klarna and vice versa, rows from before Klarna count as card`() {
        fun reusableFor(
            plan: BookingOrderPlan,
            method: BookingCheckoutMethod,
        ) = orders.findReusable(
            plan.reservationFlowId,
            plan.phaseIds,
            plan.amountMinor,
            ReservationCardOrderTx.paymentPhaseKey(plan.paymentPhaseId),
            ReservationCardOrderTx.reusableUntil(),
            method.name,
        )

        val cardPlan = plan(flowId = 901, phaseIds = "81", amountMinor = 111_100, paymentPhaseId = 81)
        val card = insert("tr_methodCard", cardPlan)
        card.method shouldBe "CARD"
        row(card.id!!).methodOrCard shouldBe BookingCheckoutMethod.CARD
        reusableFor(cardPlan, BookingCheckoutMethod.CARD)?.id shouldBe card.id
        reusableFor(cardPlan, BookingCheckoutMethod.KLARNA).shouldBeNull()

        // Same flow, phase and cents, now with Klarna: a NEW row, although the card row is still open.
        val klarnaPlan = cardPlan.copy(method = BookingCheckoutMethod.KLARNA)
        val klarna = insert("tr_methodKlarna", klarnaPlan)
        klarna.id shouldNotBe card.id
        row(klarna.id!!).methodOrCard shouldBe BookingCheckoutMethod.KLARNA
        reusableFor(cardPlan, BookingCheckoutMethod.KLARNA)?.id shouldBe klarna.id
        reusableFor(cardPlan, BookingCheckoutMethod.CARD)?.id shouldBe card.id

        // A row written before V9_124 has no method: it is a card checkout.
        jdbc.update("UPDATE reservation_card_order SET method = NULL WHERE id = ?", card.id)
        row(card.id!!).methodOrCard shouldBe BookingCheckoutMethod.CARD
        reusableFor(cardPlan, BookingCheckoutMethod.CARD)?.id shouldBe card.id
        shouldThrow<Exception> { jdbc.update("UPDATE reservation_card_order SET method = 'PAYPAL' WHERE id = ?", card.id) }
        jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN ('chk_reservation_card_order_method', 'chk_custom_payment_method')", Long::class.java) shouldBe 2L
    }

    @Test
    @Order(22)
    fun `paid after the 48 h stale review (Klarna stays open that long) - one alert, the phase stays unpaid for a human`() {
        jdbc.update("UPDATE reservation_card_order SET status = 'CANCELLED' WHERE status IN ('PENDING', 'SUPERSEDED')")
        val phase = newPhase(900)
        val order = pendingOver("tr_staleThenPaid", listOf(phase), paymentPhaseId = phase, amountMinor = 150_000)
        tx.markStaleOpen(order.id!!) shouldBe true
        with(row(order.id!!)) {
            status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
            reviewReason.shouldNotBeNull() shouldContain ReservationCardOrderTx.STALE_REASON
        }

        // Still open at Mollie: nothing to say.
        tx.applyCompleted(order.id!!, paidPayment(order).copy(status = "open"), null) shouldBe CompletionResult.NOTHING

        val late = tx.applyCompleted(order.id!!, paidPayment(order), "webhook:paid")
        late.completedNow shouldBe false
        late.promote shouldBe false
        late.alert.shouldNotBeNull() shouldContain "PAID at Mollie after the 48 hour stale review"
        with(row(order.id!!)) {
            status shouldBe ReservationCardOrderStatus.NEEDS_REVIEW
            reviewReason.shouldNotBeNull() shouldContain "NOT marked paid"
        }
        phaseRow(phase).paidOn.shouldBeNull()
        // Once: the webhook, the status endpoint and the reconcile job may all report the same payment again.
        tx.applyCompleted(order.id!!, paidPayment(order), "webhook:paid") shouldBe CompletionResult.NOTHING
        events.recorded.shouldBeEmpty()
    }
}
