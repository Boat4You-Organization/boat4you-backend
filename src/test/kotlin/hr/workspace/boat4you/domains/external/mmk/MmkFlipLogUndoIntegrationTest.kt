package hr.workspace.boat4you.domains.external.mmk

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.common.config.RealPriceSwitchesFixture
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyCounters
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.job.ReverifyDigestJob
import hr.workspace.boat4you.domains.external.nausys.service.NauSysReverifyLimits
import hr.workspace.boat4you.domains.external.service.PartnerFlipUndoService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy
import org.springframework.core.io.ClassPathResource
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Real-price WS-B common machinery against a real PostgreSQL (prod major 18) with V9_59 and V9_82 applied: the MMK
 * write query (one CTE that flips and writes `partner_offer_flip_log` in the same statement), its write scope, the
 * step 2c / 2b candidate queries, [PartnerFlipUndoService] and the A2 criterion of [ReverifyDigestJob]. The real
 * [OfferRepository] native queries run through Spring Data's JpaRepositoryFactory on a Hibernate EntityManager (recipe
 * of YachtSearchDatedOfferTest); the schema is the minimal set of tables these queries read.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MmkFlipLogUndoIntegrationTest {
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
            CREATE TABLE agency (id bigint PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true,
                                 availability_blocked boolean NOT NULL DEFAULT false);
            CREATE TABLE agency_source (agency_id bigint NOT NULL, external_system_id integer NOT NULL, "primary" boolean NOT NULL,
                                        external_id bigint NOT NULL, PRIMARY KEY (agency_id, external_system_id));
            CREATE TABLE yacht (id bigint PRIMARY KEY, name text NOT NULL, agency_id bigint, sys_active boolean NOT NULL DEFAULT true);
            CREATE TABLE external_mapping (id bigserial PRIMARY KEY, external_id bigint NOT NULL, system_id bigint NOT NULL,
                                           type varchar(100) NOT NULL, extended_type varchar(100), external_system_id integer NOT NULL);
            CREATE TABLE offer (id bigint PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL, date_to date NOT NULL,
                                status varchar(31) NOT NULL, ext_status varchar(50), client_price numeric, ext_total_price numeric);
            CREATE TABLE external_reservations (id bigserial PRIMARY KEY, yacht_id bigint, date_from date NOT NULL,
                                date_to date NOT NULL, status varchar(31) NOT NULL, option_expiration timestamp);
            CREATE TABLE reservation_flow (id bigserial PRIMARY KEY, offer_id bigint);
            CREATE TABLE reservation_extras (id bigserial PRIMARY KEY, reservation_id bigint NOT NULL);
            INSERT INTO agency (id, name) VALUES (1, 'Secret Charter Ltd'), (2, 'NauSys Agency');
            INSERT INTO agency_source VALUES (1, 1, true, 555), (2, 2, true, 666);
            INSERT INTO yacht (id, name, agency_id) VALUES (1, 'One', 1), (2, 'Two', 1), (3, 'Three', 1), (4, 'Four', 2);
            INSERT INTO external_mapping (external_id, system_id, type, extended_type, external_system_id) VALUES
                (1001, 1, 'Yacht', 'Yacht-AgencyId-1', 1), (1002, 2, 'Yacht', 'Yacht-AgencyId-1', 1),
                (1003, 3, 'Yacht', 'Yacht-AgencyId-1', 1);
            """.trimIndent()

        private val TODAY: LocalDate = LocalDate.now()
        private val F: LocalDate = TODAY.plusDays(60)
        private val T: LocalDate = F.plusDays(7)
        private const val PRICE = "1000.10"
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var emf: EntityManagerFactory
    private lateinit var em: EntityManager
    private lateinit var offers: OfferRepository
    private lateinit var runs: ReverifyRunStore
    private lateinit var checks: PartnerPeriodCheckStore
    private lateinit var undo: PartnerFlipUndoService

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 3
            }
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(MINIMAL_SCHEMA)
        listOf("V9_59__mmk_free_week_strike.sql", "V9_82__real_price_evidence.sql").forEach {
            val sql = ClassPathResource("db/migration/$it").inputStream.bufferedReader().readText()
            jdbc.execute("BEGIN; $sql; COMMIT;")
        }
        emf =
            LocalContainerEntityManagerFactoryBean().run {
                dataSource = this@MmkFlipLogUndoIntegrationTest.dataSource
                setPackagesToScan("hr.workspace.boat4you")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(
                    mapOf(
                        "hibernate.hbm2ddl.auto" to "none",
                        "hibernate.physical_naming_strategy" to CamelCaseToUnderscoresNamingStrategy::class.java.name,
                        "hibernate.implicit_naming_strategy" to SpringImplicitNamingStrategy::class.java.name,
                    ),
                )
                afterPropertiesSet()
                `object`!!
            }
        em = emf.createEntityManager()
        offers = JpaRepositoryFactory(em).getRepository(OfferRepository::class.java)
        runs = ReverifyRunStore(jdbc)
        checks = PartnerPeriodCheckStore(jdbc)
        undo = PartnerFlipUndoService(jdbc, checks, runs, DataSourceTransactionManager(dataSource))
    }

    @AfterAll
    fun tearDown() {
        em.close()
        emf.close()
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute(
            "TRUNCATE partner_offer_flip_log, reverify_run, partner_period_check, mmk_free_week_strike, offer, " +
                "external_reservations, reservation_flow",
        )
    }

    private fun offer(
        id: Long,
        status: String,
        ext: String? = "0",
        yacht: Long = 1,
        from: LocalDate = F,
    ) {
        jdbc.update(
            "INSERT INTO offer (id, yacht_id, date_from, date_to, status, ext_status, client_price, ext_total_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            yacht,
            from,
            from.plusDays(7),
            status,
            ext,
            BigDecimal(PRICE),
            BigDecimal(PRICE),
        )
    }

    private fun reservation(
        status: String,
        expiration: String? = null,
        yacht: Long = 1,
    ) {
        jdbc.update(
            "INSERT INTO external_reservations (yacht_id, date_from, date_to, status, option_expiration) VALUES (?, ?, ?, ?, CAST(? AS timestamp))",
            yacht,
            F.plusDays(1),
            T.minusDays(1),
            status,
            expiration,
        )
    }

    private fun statusOf(id: Long): String = jdbc.queryForObject("SELECT status FROM offer WHERE id = ?", String::class.java, id)!!

    private fun <T> inTx(block: () -> T): T {
        em.transaction.begin()
        try {
            return block().also { em.transaction.commit() }
        } catch (e: Exception) {
            em.transaction.rollback()
            throw e
        }
    }

    private fun mark(
        runId: Long?,
        includeOptionWaiting: Boolean,
        yacht: Long = 1,
    ) = inTx {
        offers.markMmkPeriodUnavailable(yacht, F, T, runId, ReverifyJobs.MMK_OPTION, "TWO_STRIKE_EMPTY", TODAY.minusDays(1), TODAY, includeOptionWaiting)
    }

    /** One period with every kind of row the write scope must tell apart. */
    private fun mixedPeriod() {
        offer(1, "FREE")
        offer(2, "OPTION", ext = "2") // no live hold -> in scope
        offer(3, "OPTION_WAITING", ext = "9") // in scope only with includeOptionWaiting
        offer(4, "OPTION", ext = "SYNTHETIC_OPTION") // never
        offer(5, "OPTION", ext = "2") // reservation_flow -> never
        offer(6, "FREE", from = F.minusDays(3)) // overlapping, other dates -> never
        offer(7, "FREE", yacht = 2) // other yacht -> never
        jdbc.update("INSERT INTO reservation_flow (offer_id) VALUES (5)")
    }

    // ---- write query

    @Test
    fun `the MMK write query flips FREE and unbacked OPTION rows of exactly the period and logs each flip`() {
        mixedPeriod()
        val run = runs.start(ReverifyJobs.MMK_OPTION, ReverifyRunMode.WRITE)

        offers.countMmkPeriodFlippable(1, F, T, false) shouldBe 2
        mark(run, includeOptionWaiting = false) shouldBe 2

        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L).map(::statusOf) shouldContainExactly
            listOf("UNAVAILABLE", "UNAVAILABLE", "OPTION_WAITING", "OPTION", "OPTION", "FREE", "FREE")
        val log =
            jdbc.queryForList(
                "SELECT offer_id, run_id, job, reason, external_system_id, yacht_id, date_from, date_to, old_status, old_ext_status, " +
                    "old_client_price, new_status, first_empty_on, decided_on FROM partner_offer_flip_log ORDER BY offer_id",
            )
        log.map { it["offer_id"] } shouldContainExactly listOf(1L, 2L)
        log[1]["old_status"] shouldBe "OPTION"
        log[1]["old_ext_status"] shouldBe "2"
        (log[1]["old_client_price"] as BigDecimal).compareTo(BigDecimal("1000.10")) shouldBe 0
        log.all {
            it["run_id"] == run && it["job"] == ReverifyJobs.MMK_OPTION && it["reason"] == "TWO_STRIKE_EMPTY" &&
                (it["external_system_id"] as Number).toInt() == 1 && it["new_status"] == "UNAVAILABLE" && it["yacht_id"] == 1L
        } shouldBe true
        (log[0]["first_empty_on"] as java.sql.Date).toLocalDate() shouldBe TODAY.minusDays(1)
        (log[0]["decided_on"] as java.sql.Date).toLocalDate() shouldBe TODAY
    }

    @Test
    fun `OPTION_WAITING enters the write scope only when asked to`() {
        mixedPeriod()

        offers.countMmkPeriodFlippable(1, F, T, true) shouldBe 3
        mark(null, includeOptionWaiting = true) shouldBe 3

        statusOf(3) shouldBe "UNAVAILABLE"
        listOf(4L, 5L, 6L, 7L).map(::statusOf) shouldContainExactly listOf("OPTION", "OPTION", "FREE", "FREE")
        jdbc.queryForList("SELECT old_status FROM partner_offer_flip_log ORDER BY offer_id", String::class.java) shouldContainExactly
            listOf("FREE", "OPTION", "OPTION_WAITING")
    }

    @Test
    fun `an option a live partner hold backs is never flipped, an option lapsed for more than 48 h is`() {
        offer(1, "OPTION", ext = "2")
        offer(2, "OPTION", ext = "2", yacht = 2)
        reservation("OPTION", expiration = TODAY.minusDays(1).toString(), yacht = 1) // within the 48 h echo grace
        reservation("OPTION", expiration = TODAY.minusDays(5).toString(), yacht = 2) // lapsed

        mark(null, includeOptionWaiting = true, yacht = 1) shouldBe 0
        mark(null, includeOptionWaiting = true, yacht = 2) shouldBe 1
    }

    @Test
    fun `flip and flip log are one statement - a rolled back transaction leaves neither`() {
        mixedPeriod()

        em.transaction.begin()
        offers.markMmkPeriodUnavailable(1, F, T, null, ReverifyJobs.MMK_FREE, "TWO_STRIKE_EMPTY", null, TODAY, true) shouldBe 3
        em.transaction.rollback()

        listOf(1L, 2L, 3L).map(::statusOf) shouldContainExactly listOf("FREE", "OPTION", "OPTION_WAITING")
        jdbc.queryForObject("SELECT count(*) FROM partner_offer_flip_log", Int::class.java) shouldBe 0
    }

    // ---- candidate queries

    @Test
    fun `step 2c candidates - stale options only, rotation skips yesterday, never-checked first, capped`() {
        offer(1, "OPTION", ext = "2", from = F) // checked 3 days ago
        offer(2, "OPTION_WAITING", ext = "9", from = F.plusDays(7)) // never checked
        offer(3, "OPTION", ext = "2", from = F.plusDays(14)) // checked yesterday -> skipped
        offer(4, "OPTION", ext = "SYNTHETIC_OPTION", from = F.plusDays(21))
        offer(5, "FREE", from = F.plusDays(28))
        offer(6, "OPTION", ext = "2", from = F.plusDays(35)) // flow
        offer(7, "OPTION", ext = "2", yacht = 2) // backed by a live reservation
        offer(8, "OPTION", ext = "2", yacht = 3, from = TODAY.minusDays(2)) // started
        offer(9, "OPTION", ext = "2", yacht = 4) // NauSys agency
        offer(10, "OPTION", ext = "2", yacht = 3, from = F.plusDays(3)) // never checked, later
        jdbc.update("INSERT INTO reservation_flow (offer_id) VALUES (6)")
        reservation("RESERVATION", yacht = 2)
        checks.recordChecked(ExternalSystemEnum.MMK, 1, F, T, PeriodCheckPriority.STALE_OPTION, PeriodCheckResult.EMPTY, today = TODAY.minusDays(3))
        checks.recordChecked(
            ExternalSystemEnum.MMK,
            1,
            F.plusDays(14),
            T.plusDays(14),
            PeriodCheckPriority.STALE_OPTION,
            PeriodCheckResult.QUOTED_OPTION,
            today = TODAY.minusDays(1),
        )
        val mmk = ExternalSystemEnum.MMK.value.toLong()

        val all = offers.findStaleMmkOptionPeriods(mmk, TODAY, TODAY.minusDays(1), 3000)

        all.map { it.yachtId to it.dateFrom } shouldContainExactly listOf(3L to F.plusDays(3), 1L to F.plusDays(7), 1L to F)
        all.first().externalYachtId shouldBe 1003L
        offers.findStaleMmkOptionPeriods(mmk, TODAY, TODAY.minusDays(1), 2).size shouldBe 2
    }

    @Test
    fun `step 2b candidates - open strikes from earlier days not decided today`() {
        listOf(F, F.plusDays(7), F.plusDays(14), F.plusDays(21)).forEach { offer(it.toEpochDay(), "FREE", from = it) }
        jdbc.update("INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on) VALUES (1, ?, ?, ?)", F, T, TODAY.minusDays(1))
        jdbc.update("INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on) VALUES (1, ?, ?, ?)", F.plusDays(7), T.plusDays(7), TODAY)
        jdbc.update(
            "INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on, hidden_on) VALUES (1, ?, ?, ?, ?)",
            F.plusDays(14),
            T.plusDays(14),
            TODAY.minusDays(3),
            TODAY.minusDays(2),
        )
        jdbc.update("INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on) VALUES (1, ?, ?, ?)", F.plusDays(21), T.plusDays(21), TODAY.minusDays(2))
        checks.recordChecked(ExternalSystemEnum.MMK, 1, F.plusDays(21), T.plusDays(21), PeriodCheckPriority.SAMPLE, PeriodCheckResult.EMPTY, today = TODAY)

        offers.findOpenMmkStrikePeriods(ExternalSystemEnum.MMK.value.toLong(), TODAY, 100).map { it.dateFrom } shouldContainExactly listOf(F)
    }

    @Test
    fun `the on-demand path finds only yachts that show a row with exactly those dates`() {
        offer(1, "FREE")
        offer(2, "UNAVAILABLE", yacht = 2)
        offer(3, "OPTION", ext = "SYNTHETIC_OPTION", yacht = 3)
        val mmk = ExternalSystemEnum.MMK.value.toLong()

        offers.findYachtIdsShowingMmkPeriod(mmk, 1001, F, T) shouldContainExactly listOf(1L)
        offers.findYachtIdsShowingMmkPeriod(mmk, 1002, F, T) shouldContainExactly emptyList()
        offers.findYachtIdsShowingMmkPeriod(mmk, 1003, F, T) shouldContainExactly emptyList()
        offers.findYachtIdsShowingMmkPeriod(mmk, 1001, F, T.plusDays(1)) shouldContainExactly emptyList()
        offers.findPeriodStatusPrices(1, F, T).single().let {
            it.status shouldBe "FREE"
            it.extTotalPrice!!.compareTo(BigDecimal("1000.10")) shouldBe 0
        }
    }

    // ---- undo

    @Test
    fun `undo restores the logged status and ext_status, skips rows taken since, and clears the hide records`() {
        offer(1, "FREE")
        offer(2, "OPTION_WAITING", ext = "9")
        offer(3, "FREE", yacht = 2) // a live partner reservation arrives after the flip -> stays UNAVAILABLE
        offer(4, "FREE", yacht = 3) // a zombie reservation (expired) does not block the undo
        val run = runs.start(ReverifyJobs.MMK_OPTION, ReverifyRunMode.WRITE)
        listOf(1L, 2L, 3L).forEach { y ->
            inTx { offers.markMmkPeriodUnavailable(y, F, T, run, ReverifyJobs.MMK_OPTION, "TWO_STRIKE_EMPTY", TODAY.minusDays(1), TODAY, true) }
            jdbc.update(
                "INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on, hidden_on, hidden_rows) VALUES (?, ?, ?, ?, ?, 1)",
                y,
                F,
                T,
                TODAY.minusDays(1),
                TODAY,
            )
            checks.markHidden(ExternalSystemEnum.MMK, y, F, T, 1, TODAY)
        }
        reservation("RESERVATION", yacht = 2)
        reservation("RESERVATION", expiration = TODAY.minusDays(2).toString(), yacht = 3)

        val r = undo.undoRun(run)

        r.flippedOffers shouldBe 4
        r.restored shouldBe 3
        r.skippedLiveReservation shouldBe 1
        listOf(1L, 2L, 3L, 4L).map(::statusOf) shouldContainExactly listOf("FREE", "OPTION_WAITING", "UNAVAILABLE", "FREE")
        jdbc.queryForObject("SELECT ext_status FROM offer WHERE id = 2", String::class.java) shouldBe "9"
        jdbc.queryForList("SELECT yacht_id FROM mmk_free_week_strike", Long::class.java) shouldContainExactly listOf(2L)
        listOf(1L, 2L, 3L).map { checks.find(ExternalSystemEnum.MMK, it, F, T)!!.hiddenOn } shouldContainExactly listOf(null, TODAY, null)
        r.clearedPeriods shouldBe 2 // yacht 1 (two rows, one period) and yacht 3

        undo.undoRun(run).restored shouldBe 0 // idempotent
        // The undo is recorded: its own run and one UNDO flip-log row per restored offer.
        val undoRun = jdbc.queryForMap("SELECT id, job, aborted_reason FROM reverify_run WHERE job = 'UNDO'")
        undoRun["aborted_reason"] shouldBe "undo of run #$run: 3 offers restored"
        jdbc.queryForList(
            "SELECT offer_id FROM partner_offer_flip_log WHERE run_id = ? AND reason = 'UNDO' AND old_status = 'UNAVAILABLE' ORDER BY offer_id",
            Long::class.java,
            undoRun["id"],
        ) shouldContainExactly listOf(1L, 2L, 4L)
        jdbc.queryForObject("SELECT new_status FROM partner_offer_flip_log WHERE reason = 'UNDO' AND offer_id = 2", String::class.java) shouldBe
            "OPTION_WAITING"
    }

    @Test
    fun `re-posting an old undo never overrides a later run's flip of the same offer`() {
        offer(1, "FREE")
        val runA = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE)
        mark(runA, includeOptionWaiting = false)
        undo.undoRun(runA).restored shouldBe 1
        statusOf(1) shouldBe "FREE"
        // Run B flips the same offer again on fresh two-day evidence.
        val runB = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE)
        mark(runB, includeOptionWaiting = false)
        jdbc.update(
            "INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on, hidden_on, hidden_rows) VALUES (1, ?, ?, ?, ?, 1)",
            F,
            T,
            TODAY.minusDays(1),
            TODAY,
        )

        val again = undo.undoRun(runA)

        again.restored shouldBe 0
        again.skippedSupersededByLaterRun shouldBe 1
        statusOf(1) shouldBe "UNAVAILABLE"
        jdbc.queryForObject("SELECT count(*) FROM mmk_free_week_strike WHERE hidden_on IS NOT NULL", Int::class.java) shouldBe 1
        undo.undoRun(runB).restored shouldBe 1 // run B's own undo still works
    }

    @Test
    fun `undo skips a booking in progress and a row the partner rewrote since`() {
        offer(1, "OPTION", ext = "2")
        offer(2, "FREE", yacht = 2)
        val run = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE)
        listOf(1L, 2L).forEach { y -> inTx { offers.markMmkPeriodUnavailable(y, F, T, run, ReverifyJobs.MMK_FREE, "TWO_STRIKE_EMPTY", null, TODAY, false) } }
        jdbc.update("INSERT INTO reservation_flow (offer_id) VALUES (1)")
        jdbc.update("UPDATE offer SET ext_status = '6' WHERE id = 2") // MMK itself now says "not available"

        val r = undo.undoRun(run)

        r.restored shouldBe 0
        r.skippedReservationFlow shouldBe 1
        r.skippedChangedSince shouldBe 1
        listOf(1L, 2L).map(::statusOf) shouldContainExactly listOf("UNAVAILABLE", "UNAVAILABLE")
    }

    @Test
    fun `undo of a row that is no longer UNAVAILABLE leaves it alone`() {
        offer(1, "FREE")
        val run = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE)
        mark(run, includeOptionWaiting = false)
        jdbc.update("UPDATE offer SET status = 'OPTION', ext_status = '2' WHERE id = 1") // the partner quoted an option since

        val r = undo.undoRun(run)

        r.skippedNoLongerUnavailable shouldBe 1
        statusOf(1) shouldBe "OPTION"
    }

    // ---- digest

    @Test
    fun `the digest prints the runs, the flips, the escalated agency ids and the A2 count, never an agency name`() {
        offer(1, "OPTION", ext = "2") // no evidence -> counts
        offer(2, "OPTION_WAITING", ext = "9", yacht = 2) // checked 2 days ago -> evidence, does not count
        offer(3, "OPTION", ext = "SYNTHETIC_OPTION", yacht = 3) // never counts
        offer(4, "OPTION", ext = "UNDER_OPTION", yacht = 4) // NauSys, no evidence -> counts
        checks.recordChecked(ExternalSystemEnum.MMK, 2, F, T, PeriodCheckPriority.STALE_OPTION, PeriodCheckResult.QUOTED_OPTION, today = TODAY.minusDays(2))
        val run = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE)
        offer(5, "FREE", yacht = 3, from = F.plusDays(7))
        inTx { offers.markMmkPeriodUnavailable(3, F.plusDays(7), T.plusDays(7), run, ReverifyJobs.MMK_FREE, "TWO_STRIKE_EMPTY", null, TODAY, false) }
        runs.finish(run, abortedReason = null, escalatedAgencyIds = listOf(1L, 77L))
        val job = digestJob()

        val body = job.buildDigest()

        body shouldContain "#$run MMK_FREE WRITE"
        body shouldContain "TWO_STRIKE_EMPTY: 1 termina, 1 redaka"
        body shouldContain "agencija 1:"
        body shouldContain "agencija 77:"
        body shouldContain "ukupno: 2 (MMK 1, NAUSYS 1)"
        body shouldNotContain "Secret Charter"
        body shouldNotContain "NauSys Agency"
        jdbc.queryForList(ReverifyDigestJob.A2_CRITERION_SQL).map { it["rows"] } shouldContainExactlyInAnyOrder listOf(1L, 1L)

        job.sendDigest()
        val mail = mails.single()
        mail[0] shouldBe listOf("admin@example.test")
        mail[1] shouldBe "⚠️ Provjere kod partnera - dnevni pregled (Boat4You)"
        mail[2] shouldBe ReverifyDigestJob.TEMPLATE
        val mailed = (mail[3] as Map<*, *>)["reportBody"] as String
        mailed shouldStartWith "ZAŠTO OVAJ MAIL\n  NOVA ESKALACIJA: agencija 1 (prethodnih 6 dana nije bila eskalirana)\n" +
            "  NOVA ESKALACIJA: agencija 77 (prethodnih 6 dana nije bila eskalirana)\n\nPROVJERE KOD PARTNERA"
        mailed shouldContain "A2 KRITERIJ"
    }

    @Test
    fun `each flip is listed under the run and job that wrote it, with its old statuses and agency ids`() {
        // 20.10.2026: Y Knot's phantom OPTION rows were hidden by MMK_OPTION step 2c (run #38), Sailway's FREE weeks by
        // MMK_FREE (run #37); the digest named both jobs right, only the agencies behind each line were missing.
        jdbc.update("INSERT INTO agency (id, name) VALUES (3, 'Phantom Options Ltd') ON CONFLICT (id) DO NOTHING")
        jdbc.update("INSERT INTO yacht (id, name, agency_id) VALUES (5, 'Five', 3) ON CONFLICT (id) DO NOTHING")
        offer(1, "FREE", yacht = 3)
        offer(2, "OPTION", ext = "2", yacht = 5)
        offer(3, "OPTION", ext = "2", yacht = 5, from = F.plusDays(3))
        val free = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE)
        val option = runs.start(ReverifyJobs.MMK_OPTION, ReverifyRunMode.WRITE)
        val firstEmptyOn = TODAY.minusDays(1)
        inTx { offers.markMmkPeriodUnavailable(3, F, T, free, ReverifyJobs.MMK_FREE, "TWO_STRIKE_EMPTY", firstEmptyOn, TODAY, false) }
        inTx {
            offers.markMmkPeriodUnavailable(5, F, T, option, ReverifyJobs.MMK_OPTION, "TWO_STRIKE_EMPTY", firstEmptyOn, TODAY, true)
            offers.markMmkPeriodUnavailable(5, F.plusDays(3), T.plusDays(3), option, ReverifyJobs.MMK_OPTION, "TWO_STRIKE_EMPTY", firstEmptyOn, TODAY, true)
        }
        runs.finish(free)
        runs.finish(option)
        runs.finish(runs.start(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.LOG), ReverifyCounters(calls = 6000, wouldFlipRows = 2093))

        val body = digestJob().buildDigest()

        body shouldContain "  run #$free MMK_FREE MMK TWO_STRIKE_EMPTY: 1 termina, 1 redaka -> UNAVAILABLE\n      prije: FREE 1; agencije (id: redaka): 1: 1\n"
        body shouldContain "  run #$option MMK_OPTION MMK TWO_STRIKE_EMPTY: 2 termina, 2 redaka -> UNAVAILABLE\n      prije: OPTION 2; agencije (id: redaka): 3: 2\n"
        body shouldContain "NAUSYS_REVERIFY LOG"
        body shouldContain "wouldFlip=2093 (korak 2 ograničen na capP3=2000 perioda/dan;"
        body shouldNotContain "Phantom Options"
    }

    @Test
    fun `a quiet day is not mailed, the digest stays in the log`() {
        offer(1, "OPTION", ext = "2")
        runs.finish(runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE))
        val job = digestJob()

        job.buildDigest() shouldStartWith "PROVJERE KOD PARTNERA"
        job.sendDigest()

        mails shouldBe emptyList()
    }

    @Test
    fun `escalations read new against the 6 days before and persistent on 7 days`() {
        fun escalatedDaysAgo(
            days: Int,
            vararg agencies: Long,
        ) {
            val run = runs.start(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.LOG)
            runs.finish(run, escalatedAgencyIds = agencies.toList())
            jdbc.update("UPDATE reverify_run SET started_at = started_at - make_interval(days => ?) WHERE id = ?", days, run)
        }
        runs.finish(runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE))
        escalatedDaysAgo(3, 5L)
        escalatedDaysAgo(0, 5L, 6L)
        (0..6).forEach { escalatedDaysAgo(it, 9L) }
        escalatedDaysAgo(8, 10L) // outside the 7-day window
        escalatedDaysAgo(0, 10L)

        val body = digestJob().buildDigest()

        body shouldStartWith "ZAŠTO OVAJ MAIL\n"
        body shouldContain "NOVA ESKALACIJA: agencija 6 "
        body shouldContain "NOVA ESKALACIJA: agencija 10 "
        body shouldNotContain "NOVA ESKALACIJA: agencija 5 "
        body shouldNotContain "NOVA ESKALACIJA: agencija 9 "
        body shouldContain "TRAJNA ESKALACIJA: agencija 9, 7 dana u zadnjih 7 - treba odluka"
        body shouldNotContain "TRAJNA ESKALACIJA: agencija 5"
        body shouldContain "agencija 9: NAUSYS_REVERIFY, 7 dana"
    }

    private val mails = mutableListOf<List<Any?>>()

    private fun digestJob(): ReverifyDigestJob {
        mails.clear()
        val email =
            mock(EmailService::class.java) { inv ->
                if (inv.method.name == "sendEmail") mails += inv.arguments.toList()
                null
            }
        val users = mock(UserRepository::class.java)
        `when`(users.findAllAdminEmailAddresses()).thenReturn(listOf("admin@example.test"))
        return ReverifyDigestJob(jdbc, email, users, RealPriceSwitchesFixture.switches(), NauSysReverifyLimits(), runs)
    }
}
