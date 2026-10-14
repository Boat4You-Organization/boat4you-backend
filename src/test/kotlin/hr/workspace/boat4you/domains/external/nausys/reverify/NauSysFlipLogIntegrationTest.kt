package hr.workspace.boat4you.domains.external.nausys.reverify

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.service.PartnerFlipUndoService
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Real-price WS-B2 against a real PostgreSQL (prod major 18) with V9_82 applied: the NauSys write query (one CTE that
 * flips and writes `partner_offer_flip_log` in the same statement) and its write scope, the reverifier's candidate
 * classes P1-P6, step 0, the path-4 shown-rows read, the purge hook and an undo of a NauSys run. The real
 * [OfferRepository] native queries run through Spring Data's JpaRepositoryFactory (recipe of MmkFlipLogUndoIntegrationTest).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NauSysFlipLogIntegrationTest {
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
                                status varchar(31) NOT NULL, ext_status varchar(50), client_price numeric, ext_total_price numeric,
                                location_from bigint NOT NULL DEFAULT 10, location_to bigint NOT NULL DEFAULT 10);
            CREATE TABLE external_reservations (id bigserial PRIMARY KEY, yacht_id bigint, date_from date NOT NULL,
                                date_to date NOT NULL, status varchar(31) NOT NULL, option_expiration timestamp);
            CREATE TABLE reservation_flow (id bigserial PRIMARY KEY, offer_id bigint);
            CREATE TABLE reservation_extras (id bigserial PRIMARY KEY, reservation_id bigint NOT NULL);
            CREATE TABLE reservation_options (id bigserial PRIMARY KEY, yacht_id bigint NOT NULL, date_from date NOT NULL,
                                date_to date, minimal_duration smallint NOT NULL);
            INSERT INTO agency (id, name) VALUES (1, 'Mmk Agency'), (2, 'NauSys Agency'), (3, 'Blocked NauSys Agency');
            UPDATE agency SET availability_blocked = true WHERE id = 3;
            INSERT INTO agency_source VALUES (1, 1, true, 555), (2, 2, true, 666), (3, 2, true, 777);
            INSERT INTO yacht (id, name, agency_id) VALUES (1, 'One', 2), (2, 'Two', 2), (3, 'Three', 2), (4, 'Mmk', 1), (5, 'Blocked', 3);
            INSERT INTO external_mapping (external_id, system_id, type, extended_type, external_system_id) VALUES
                (2001, 1, 'Yacht', 'Yacht-AgencyId-2', 2), (2002, 2, 'Yacht', 'Yacht-AgencyId-2', 2),
                (2003, 3, 'Yacht', 'Yacht-AgencyId-2', 2), (1004, 4, 'Yacht', 'Yacht-AgencyId-1', 1),
                (2005, 5, 'Yacht', 'Yacht-AgencyId-3', 2);
            """.trimIndent()

        private val TODAY: LocalDate = LocalDate.now()

        /** A Saturday about two months ahead (grid shape). */
        private val SAT: LocalDate = TODAY.plusDays(60).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        private val F: LocalDate = SAT
        private val T: LocalDate = SAT.plusDays(7)
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var emf: EntityManagerFactory
    private lateinit var em: EntityManager
    private lateinit var offers: OfferRepository
    private lateinit var runs: ReverifyRunStore
    private lateinit var checks: PartnerPeriodCheckStore
    private lateinit var undo: PartnerFlipUndoService
    private val nausys = ExternalSystemEnum.NAUSYS.value.toLong()

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
                dataSource = this@NauSysFlipLogIntegrationTest.dataSource
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
            "TRUNCATE partner_offer_flip_log, reverify_run, partner_period_check, offer, external_reservations, reservation_flow, " +
                "reservation_options",
        )
    }

    @Suppress("LongParameterList")
    private fun offer(
        id: Long,
        status: String,
        ext: String? = "FREE",
        yacht: Long = 1,
        from: LocalDate = F,
        nights: Long = 7,
        route: Pair<Long, Long> = 10L to 10L,
    ) {
        jdbc.update(
            "INSERT INTO offer (id, yacht_id, date_from, date_to, status, ext_status, client_price, ext_total_price, location_from, location_to) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            yacht,
            from,
            from.plusDays(nights),
            status,
            ext,
            BigDecimal("1500.00"),
            BigDecimal("1500.00"),
            route.first,
            route.second,
        )
    }

    private fun reservation(
        status: String,
        expiration: LocalDateTime? = null,
        yacht: Long = 1,
        from: LocalDate = F.plusDays(1),
        to: LocalDate = T.minusDays(1),
    ) {
        jdbc.update(
            "INSERT INTO external_reservations (yacht_id, date_from, date_to, status, option_expiration) VALUES (?, ?, ?, ?, ?)",
            yacht,
            from,
            to,
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

    @Suppress("LongParameterList")
    private fun mark(
        runId: Long?,
        offerId: Long? = null,
        occupancy: Boolean = false,
        yacht: Long = 1,
        reason: String = "TWO_STRIKE_EMPTY",
    ) = inTx {
        offers.markNauSysPeriodUnavailable(yacht, F, T, offerId, occupancy, runId, ReverifyJobs.NAUSYS_REVERIFY, reason, TODAY.minusDays(1), TODAY)
    }

    /** One period with every kind of row the NauSys write scope must tell apart. */
    private fun mixedPeriod() {
        offer(1, "FREE")
        offer(2, "OPTION", ext = "OPTION") // no live hold -> in scope
        offer(3, "OPTION_WAITING", ext = "SYNTHETIC_DISAPPEARANCE") // our marker -> in scope
        offer(4, "OPTION_WAITING", ext = "UNDER_OPTION") // real partner hold -> never
        offer(5, "OPTION", ext = "SYNTHETIC_OPTION") // never
        offer(6, "FREE") // reservation_flow -> never
        offer(7, "FREE", from = F.minusDays(3)) // overlapping, other dates -> never
        offer(8, "FREE", yacht = 2) // other yacht -> never
        jdbc.update("INSERT INTO reservation_flow (offer_id) VALUES (6)")
    }

    // ---- write query

    @Test
    fun `the NauSys write query flips FREE, unbacked OPTION and our marker of exactly the period, never UNDER_OPTION or a flow row`() {
        mixedPeriod()
        val run = runs.start(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.WRITE)

        offers.countNauSysPeriodFlippable(1, F, T, null, false) shouldBe 3
        mark(run) shouldBe 3

        (1L..8L).map(::statusOf) shouldContainExactly
            listOf("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "OPTION_WAITING", "OPTION", "FREE", "FREE", "FREE")
        val log =
            jdbc.queryForList(
                "SELECT offer_id, run_id, job, reason, external_system_id, old_status, old_ext_status, old_client_price, new_status, " +
                    "first_empty_on, decided_on FROM partner_offer_flip_log ORDER BY offer_id",
            )
        log.map { it["offer_id"] } shouldContainExactly listOf(1L, 2L, 3L)
        log[2]["old_status"] shouldBe "OPTION_WAITING"
        log[2]["old_ext_status"] shouldBe "SYNTHETIC_DISAPPEARANCE"
        (log[0]["old_client_price"] as BigDecimal).compareTo(BigDecimal("1500.00")) shouldBe 0
        log.all {
            it["run_id"] == run && it["job"] == ReverifyJobs.NAUSYS_REVERIFY && it["reason"] == "TWO_STRIKE_EMPTY" &&
                (it["external_system_id"] as Number).toInt() == 2 && it["new_status"] == "UNAVAILABLE"
        } shouldBe true
        (log[0]["first_empty_on"] as java.sql.Date).toLocalDate() shouldBe TODAY.minusDays(1)
    }

    @Test
    fun `write scope - a real UNDER_OPTION row and a FREE row on the same dates, only the FREE row flips`() {
        offer(1, "OPTION_WAITING", ext = "UNDER_OPTION")
        offer(2, "FREE")

        mark(null) shouldBe 1

        statusOf(1) shouldBe "OPTION_WAITING"
        statusOf(2) shouldBe "UNAVAILABLE"
        jdbc.queryForList("SELECT offer_id FROM partner_offer_flip_log", Long::class.java) shouldContainExactly listOf(2L)
    }

    @Test
    fun `an option a live partner hold backs is never flipped on emptiness, one lapsed for more than 48 h is`() {
        offer(1, "OPTION", ext = "OPTION")
        offer(2, "OPTION", ext = "OPTION", yacht = 2)
        reservation("OPTION", expiration = LocalDateTime.now().minusHours(20), yacht = 1) // within the 48 h echo grace
        reservation("OPTION", expiration = LocalDateTime.now().minusDays(5), yacht = 2) // lapsed

        mark(null, yacht = 1) shouldBe 0
        mark(null, yacht = 2) shouldBe 1
    }

    @Test
    fun `with an offer id only that row of the period flips (stale route)`() {
        offer(1, "FREE", route = 10L to 10L)
        offer(2, "FREE", route = 11L to 11L)

        offers.countNauSysPeriodFlippable(1, F, T, 2, false) shouldBe 1
        mark(null, offerId = 2, reason = "ROUTE_GONE") shouldBe 1

        statusOf(1) shouldBe "FREE"
        statusOf(2) shouldBe "UNAVAILABLE"
    }

    @Test
    fun `occupancy variant - a live reservation flips, a zombie reservation and a live option alone do not`() {
        offer(1, "FREE")
        offer(2, "OPTION", ext = "OPTION")
        offer(3, "FREE", yacht = 2)
        offer(4, "FREE", yacht = 3)
        offer(5, "OPTION_WAITING", ext = "UNDER_OPTION")
        reservation("RESERVATION", yacht = 1)
        reservation("RESERVATION", expiration = LocalDateTime.now().minusDays(2), yacht = 2) // zombie
        reservation("OPTION", expiration = LocalDateTime.now().plusDays(2), yacht = 3)

        offers.findNauSysOccupiedShownPeriods(nausys, TODAY, 100).map { it.yachtId } shouldContainExactly listOf(1L)
        offers.countNauSysPeriodFlippable(1, F, T, null, true) shouldBe 2
        mark(null, occupancy = true, reason = "STEP0_OCCUPANCY") shouldBe 2
        mark(null, occupancy = true, yacht = 2) shouldBe 0
        mark(null, occupancy = true, yacht = 3) shouldBe 0

        statusOf(5) shouldBe "OPTION_WAITING"
        statusOf(3) shouldBe "FREE"
        statusOf(4) shouldBe "FREE"
    }

    @Test
    fun `flip and flip log are one statement - a rolled back transaction leaves neither`() {
        mixedPeriod()

        em.transaction.begin()
        offers.markNauSysPeriodUnavailable(1, F, T, null, false, null, ReverifyJobs.NAUSYS_D3, "ROUTE_GONE", null, TODAY) shouldBe 3
        em.transaction.rollback()

        listOf(1L, 2L, 3L).map(::statusOf) shouldContainExactly listOf("FREE", "OPTION", "OPTION_WAITING")
        jdbc.queryForObject("SELECT count(*) FROM partner_offer_flip_log", Int::class.java) shouldBe 0
    }

    @Test
    fun `undo of a NauSys run restores our marker as OPTION_WAITING with its ext_status and clears the hide`() {
        mixedPeriod()
        val run = runs.start(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.WRITE)
        mark(run) shouldBe 3
        checks.markHidden(ExternalSystemEnum.NAUSYS, 1, F, T, 3, TODAY)

        undo.undoRun(run).restored shouldBe 3

        listOf(1L, 2L, 3L).map(::statusOf) shouldContainExactly listOf("FREE", "OPTION", "OPTION_WAITING")
        jdbc.queryForObject("SELECT ext_status FROM offer WHERE id = 3", String::class.java) shouldBe "SYNTHETIC_DISAPPEARANCE"
        checks.find(ExternalSystemEnum.NAUSYS, 1, F, T)!!.hiddenOn shouldBe null
    }

    // ---- candidates

    private fun candidates() =
        offers
            .findNauSysVerifyCandidates(nausys, TODAY, 100, 100, 100, 100, 100, 100, 100)
            .map { Triple(it.yachtId, it.dateFrom, it.cls) }

    @Test
    fun `candidate classes P1 to P6 with their guards`() {
        val wed = F.plusDays(4) // a Wednesday: off-grid
        offer(1, "OPTION_WAITING", ext = "SYNTHETIC_DISAPPEARANCE", from = F) // P1
        offer(2, "OPTION", ext = "OPTION", from = F.plusDays(7)) // P2
        offer(3, "OPTION_WAITING", ext = "UNDER_OPTION", from = F.plusDays(14)) // P2b
        offer(4, "FREE", from = wed) // P4 (Wed-Wed)
        offer(5, "FREE", from = F.plusDays(21)) // grid -> P5 sample
        offer(6, "UNAVAILABLE", ext = "RESERVATION", from = F.plusDays(28)) // P6 (no live reservation)
        offer(7, "UNAVAILABLE", ext = "RESERVATION", from = F.plusDays(35)) // live reservation -> not P6
        reservation("RESERVATION", from = F.plusDays(36), to = F.plusDays(40))
        offer(8, "OPTION", ext = "SYNTHETIC_OPTION", from = F.plusDays(42)) // never
        offer(9, "OPTION", ext = "OPTION", from = F.plusDays(49)) // flow -> not P2
        jdbc.update("INSERT INTO reservation_flow (offer_id) VALUES (9)")
        offer(10, "FREE", yacht = 4, from = F) // MMK yacht -> never
        offer(11, "FREE", yacht = 5, from = wed) // availability_blocked agency -> never
        offer(12, "FREE", yacht = 2, from = TODAY.minusDays(1)) // started -> never

        candidates() shouldContainExactlyInAnyOrder
            listOf(
                Triple(1L, F, 1),
                Triple(1L, F.plusDays(7), 2),
                Triple(1L, F.plusDays(14), 3),
                Triple(1L, wed, 5),
                Triple(1L, F.plusDays(21), 6),
                Triple(1L, F.plusDays(28), 7),
            )
        val p1 = offers.findNauSysVerifyCandidates(nausys, TODAY, 100, 100, 100, 100, 100, 100, 100).first { it.cls == 1 }
        p1.externalYachtId shouldBe 2001L
        p1.agencyId shouldBe 2L
        p1.shownRows shouldBe "1:10>10"
    }

    @Test
    fun `rotation - P1 checked within 7 days and P4 within 14 days are skipped, an open strike is re-asked the next day`() {
        val wed = F.plusDays(4)
        offer(1, "OPTION_WAITING", ext = "SYNTHETIC_DISAPPEARANCE", from = F)
        offer(2, "FREE", from = wed)
        offer(3, "FREE", yacht = 2, from = F) // grid row with an open strike from yesterday
        checks.recordChecked(ExternalSystemEnum.NAUSYS, 1, F, T, 1, PeriodCheckResult.QUOTED_OPTION, today = TODAY.minusDays(6))
        checks.recordChecked(ExternalSystemEnum.NAUSYS, 1, wed, wed.plusDays(7), 4, PeriodCheckResult.QUOTED_FREE, today = TODAY.minusDays(13))
        checks.recordFirstStrike(ExternalSystemEnum.NAUSYS, 2, F, T, 3, PeriodCheckResult.UNPRICED, TODAY.minusDays(1))

        candidates() shouldContainExactly listOf(Triple(2L, F, 4))

        // decided today -> not again today
        checks.recordFirstStrike(ExternalSystemEnum.NAUSYS, 2, F, T, 3, PeriodCheckResult.UNPRICED, TODAY)
        candidates() shouldContainExactly emptyList()
    }

    @Test
    fun `a period hidden by us in the last 7 days is skipped, a min-stay season is reported`() {
        offer(1, "UNAVAILABLE", ext = "FREE", from = F)
        offer(2, "FREE", yacht = 2, from = F)
        checks.markHidden(ExternalSystemEnum.NAUSYS, 1, F, T, 1, TODAY.minusDays(2))
        jdbc.update("INSERT INTO reservation_options (yacht_id, date_from, date_to, minimal_duration) VALUES (2, ?, ?, 14)", F.minusDays(30), F.plusDays(60))

        val c = offers.findNauSysVerifyCandidates(nausys, TODAY, 100, 100, 100, 100, 100, 100, 100)

        c.map { it.yachtId to it.cls } shouldContainExactly listOf(2L to 6)
        c.single().seasonMinDuration shouldBe 14
    }

    @Test
    fun `P5 samples first, middle and last grid period per yacht and year, capped per class`() {
        val weeks = (0 until 5).map { F.plusWeeks(it.toLong()) }.filter { it.year == F.year }
        weeks.forEachIndexed { i, d -> offer(100L + i, "FREE", from = d) }

        val sampled = offers.findNauSysVerifyCandidates(nausys, TODAY, 100, 100, 100, 100, 100, 100, 100).map { it.dateFrom }.sorted()

        val expected = listOf(weeks.first(), weeks[(weeks.size + 1) / 2 - 1], weeks.last()).distinct()
        sampled shouldContainExactly expected
        offers.findNauSysVerifyCandidates(nausys, TODAY, 100, 100, 100, 100, 100, 1, 100).size shouldBe 1
    }

    // ---- path 4 and the purge hook

    @Test
    fun `path 4 reads the shown rows of exactly the period behind one NauSys yacht id`() {
        offer(1, "FREE", route = 10L to 11L)
        offer(2, "UNAVAILABLE", ext = "RESERVATION")
        offer(3, "OPTION", ext = "SYNTHETIC_OPTION")
        offer(4, "FREE", from = F.plusDays(7))

        offers.findNauSysShownPeriodRows(nausys, 2001, F, T).map { listOf(it.offerId, it.yachtId, it.locationFromId, it.locationToId) } shouldContainExactly
            listOf(listOf(1L, 1L, 10L, 11L))
    }

    @Test
    fun `purge hook queues the NauSys OPTION offers under a lapsed hold with priority 2, nothing else`() {
        offer(1, "OPTION", ext = "OPTION")
        offer(2, "FREE", yacht = 1, from = F.plusDays(7))
        offer(3, "OPTION", ext = "OPTION", yacht = 4) // MMK yacht
        offer(4, "OPTION", ext = "OPTION", yacht = 2) // hold still live
        reservation("OPTION", expiration = LocalDateTime.now().minusDays(5), yacht = 1)
        reservation("OPTION", expiration = LocalDateTime.now().minusDays(5), yacht = 4)
        reservation("OPTION", expiration = LocalDateTime.now().plusDays(5), yacht = 2)
        checks.recordChecked(ExternalSystemEnum.NAUSYS, 1, F, T, PeriodCheckPriority.SAMPLE, PeriodCheckResult.QUOTED_OPTION, today = TODAY.minusDays(3))

        inTx { offers.queueNauSysOptionsUnderExpiringHolds(nausys, LocalDateTime.now().minusHours(48), PeriodCheckPriority.STALE_OPTION) } shouldBe 1

        val rows = jdbc.queryForList("SELECT external_system_id, yacht_id, priority, last_result FROM partner_period_check ORDER BY yacht_id")
        rows.size shouldBe 1
        (rows[0]["priority"] as Number).toInt() shouldBe 2
        rows[0]["last_result"] shouldBe "QUOTED_OPTION" // evidence columns untouched
    }
}
