package hr.workspace.boat4you.domains.external.evidence

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum.MMK
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum.NAUSYS
import hr.workspace.boat4you.domains.external.job.RealPriceEvidenceRetentionJob
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

/**
 * V9_82 and the two evidence stores plus the retention job against a real PostgreSQL 18 (prod major, cusma4). Same
 * approach as ReviewCollectionIntegrationTest: no Spring context and no full Flyway chain (it cannot be replayed on an
 * empty database), a minimal schema with the two tables V9_82 references, and the migration run twice in a
 * transaction like Flyway (it uses SET LOCAL) to prove it is re-runnable.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealPriceEvidenceStoresTest {
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
            INSERT INTO yacht (id, name) VALUES (1, 'one'), (2, 'two'), (3, 'three');
            INSERT INTO reservation_extras (reservation_id, name, price) VALUES (10, 'Skipper', 1200);
            """.trimIndent()

        private val D1: LocalDate = LocalDate.of(2027, 7, 3)
        private val D2: LocalDate = LocalDate.of(2027, 7, 10)
        private val D3: LocalDate = LocalDate.of(2027, 7, 17)
        private val DAY1: LocalDate = LocalDate.of(2026, 10, 14)
        private val DAY2: LocalDate = DAY1.plusDays(1)
        private val DAY3: LocalDate = DAY1.plusDays(2)
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var checks: PartnerPeriodCheckStore
    private lateinit var runs: ReverifyRunStore

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
        val migration = ClassPathResource("db/migration/V9_82__real_price_evidence.sql").inputStream.bufferedReader().readText()
        jdbc.execute("BEGIN; $migration; COMMIT;")
        jdbc.execute("BEGIN; $migration; COMMIT;")
        checks = PartnerPeriodCheckStore(jdbc)
        runs = ReverifyRunStore(jdbc)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE partner_offer_flip_log, reverify_run, partner_period_check")
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun columnType(
        table: String,
        column: String,
    ): String? =
        jdbc
            .queryForList(
                "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a " +
                    "WHERE a.attrelid = ?::regclass AND a.attname = ? AND NOT a.attisdropped",
                String::class.java,
                table,
                column,
            ).firstOrNull()

    // ---- V9_82

    @Test
    fun `migration creates the three tables and reservation_extras payment_type, types match offer`() {
        listOf("partner_period_check", "reverify_run", "partner_offer_flip_log").forEach { count(it) shouldBe 0 }
        columnType("reservation_extras", "payment_type") shouldBe "character varying(31)"
        jdbc.queryForObject("SELECT count(*) FROM reservation_extras WHERE payment_type IS NULL", Int::class.java) shouldBe 1
        columnType("partner_offer_flip_log", "old_status") shouldBe "character varying(31)"
        columnType("partner_offer_flip_log", "old_ext_status") shouldBe "character varying(50)"
        columnType("partner_offer_flip_log", "old_client_price") shouldBe "numeric"
        columnType("reverify_run", "escalated_agency_ids") shouldBe "bigint[]"
        columnType("partner_period_check", "last_result") shouldBe "character varying(16)"
        PeriodCheckResult.entries.forEach { (it.name.length <= 16) shouldBe true }
    }

    @Test
    fun `period checks carry no FK on yacht - a yacht delete never waits on them, retention reaps the rows`() {
        // No FK on purpose (V9_82 header): an FK would lock yacht during the migration behind an open sync transaction.
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE conrelid = 'partner_period_check'::regclass AND contype = 'f'",
            Int::class.java,
        ) shouldBe 0
        jdbc.update("INSERT INTO yacht (id, name) VALUES (99, 'gone')")
        checks.upsertPending(MMK, 99, D1, D2, 3)
        jdbc.update("DELETE FROM yacht WHERE id = 99")
        count("partner_period_check") shouldBe 1
    }

    // ---- PartnerPeriodCheckStore

    @Test
    fun `recordChecked is an idempotent upsert, priority only moves down`() {
        checks.recordChecked(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.QUOTED_FREE, today = DAY1)
        checks.recordChecked(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.QUOTED_FREE, today = DAY1)
        count("partner_period_check") shouldBe 1
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            priority shouldBe 5
            lastCheckedOn shouldBe DAY1
            lastQuotedOn shouldBe DAY1
            lastResult shouldBe PeriodCheckResult.QUOTED_FREE
            firstEmptyOn shouldBe null
        }

        checks.recordChecked(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.EMPTY, today = DAY2)
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            priority shouldBe 3
            lastCheckedOn shouldBe DAY2
            lastQuotedOn shouldBe DAY1 // an empty answer keeps the last quote day
            lastResult shouldBe PeriodCheckResult.EMPTY
        }

        checks.recordChecked(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.QUOTED_OPTION, today = DAY3)
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            priority shouldBe 3 // LEAST(3, 5)
            lastQuotedOn shouldBe DAY3
            lastResult shouldBe PeriodCheckResult.QUOTED_OPTION
        }
    }

    @Test
    fun `recordChecked with quoted=false does not move last_quoted_on`() {
        checks.recordChecked(MMK, 1, D1, D2, 2, PeriodCheckResult.QUOTED_OTHER, quoted = false, today = DAY1)
        checks.find(MMK, 1, D1, D2)!!.lastQuotedOn shouldBe null
    }

    @Test
    fun `UNKNOWN creates a pending row and never overwrites a decided answer`() {
        checks.recordChecked(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.UNKNOWN, today = DAY1)
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            lastCheckedOn shouldBe null
            lastResult shouldBe null
        }
        checks.recordChecked(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.EMPTY, today = DAY1)
        checks.recordChecked(NAUSYS, 1, D1, D2, 2, PeriodCheckResult.UNKNOWN, today = DAY2)
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            lastCheckedOn shouldBe DAY1
            lastResult shouldBe PeriodCheckResult.EMPTY
            priority shouldBe 2
        }
    }

    @Test
    fun `partners and periods are separate rows`() {
        checks.recordChecked(MMK, 1, D1, D2, 5, PeriodCheckResult.QUOTED_FREE, today = DAY1)
        checks.recordChecked(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.EMPTY, today = DAY1)
        checks.recordChecked(NAUSYS, 1, D1, D3, 5, PeriodCheckResult.EMPTY, today = DAY1)
        count("partner_period_check") shouldBe 3
        checks.find(MMK, 1, D1, D2)!!.lastResult shouldBe PeriodCheckResult.QUOTED_FREE
    }

    @Test
    fun `first_empty_on is set once, a later strike returns the first day`() {
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 4, PeriodCheckResult.EMPTY, DAY1) shouldBe DAY1
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.UNPRICED, DAY2) shouldBe DAY1
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            firstEmptyOn shouldBe DAY1
            lastCheckedOn shouldBe DAY2
            lastResult shouldBe PeriodCheckResult.UNPRICED
            priority shouldBe 4
            lastQuotedOn shouldBe null
        }
    }

    @Test
    fun `clearStrike closes strike and hide, a new strike starts a new cycle`() {
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.EMPTY, DAY1)
        checks.markHidden(NAUSYS, 1, D1, D2, 2, DAY2)
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            hiddenOn shouldBe DAY2
            hiddenRows shouldBe 2
        }

        checks.clearStrike(NAUSYS, 1, D1, D2) shouldBe 1
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            firstEmptyOn shouldBe null
            hiddenOn shouldBe null
            hiddenRows shouldBe 0
            priority shouldBe 3 // unchanged without an explicit priority
            lastResult shouldBe PeriodCheckResult.EMPTY
        }
        checks.clearStrike(NAUSYS, 1, D1, D2, priority = PeriodCheckPriority.SAMPLE)
        checks.find(NAUSYS, 1, D1, D2)!!.priority shouldBe 5
        checks.clearStrike(NAUSYS, 2, D1, D2) shouldBe 0

        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.EMPTY, DAY3) shouldBe DAY3
    }

    @Test
    fun `a strike on a period hidden earlier closes the hide record and starts a new two-day cycle`() {
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.EMPTY, DAY1) shouldBe DAY1
        checks.markHidden(NAUSYS, 1, D1, D2, 2, DAY2)
        // a later partner quote shows the period again without anyone closing the strike; one negative answer later:
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.UNPRICED, DAY3) shouldBe DAY3
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            firstEmptyOn shouldBe DAY3
            hiddenOn shouldBe null
            hiddenRows shouldBe 0
            lastResult shouldBe PeriodCheckResult.UNPRICED
        }
        // the cycle goes on as usual: an open strike is never moved
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.EMPTY, DAY3.plusDays(1)) shouldBe DAY3

        // the batch variant resets the same way
        checks.markHidden(NAUSYS, 1, D1, D2, 1, DAY3.plusDays(1))
        checks.recordFirstStrikeBatch(NAUSYS, listOf(PeriodCheck(1, D1, D2, 3, PeriodCheckResult.EMPTY)), DAY3.plusDays(2))
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            firstEmptyOn shouldBe DAY3.plusDays(2)
            hiddenOn shouldBe null
        }
    }

    @Test
    fun `closeStrikesOnQuoteBatch closes open strikes and hide records only, priority and bookkeeping stay`() {
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.EMPTY, DAY1)
        checks.recordFirstStrike(NAUSYS, 2, D1, D2, 3, PeriodCheckResult.EMPTY, DAY1)
        checks.markHidden(NAUSYS, 2, D1, D2, 2, DAY2)
        checks.recordChecked(NAUSYS, 3, D1, D2, 2, PeriodCheckResult.QUOTED_OPTION, today = DAY1) // no strike
        checks.recordFirstStrike(MMK, 1, D1, D2, 3, PeriodCheckResult.EMPTY, DAY1) // other partner
        val xminBefore = jdbc.queryForObject("SELECT xmin::text FROM partner_period_check WHERE yacht_id = 3", String::class.java)

        checks.closeStrikesOnQuoteBatch(
            NAUSYS,
            listOf(PeriodKey(1, D1, D2), PeriodKey(2, D1, D2), PeriodKey(3, D1, D2), PeriodKey(9, D1, D2), PeriodKey(1, D1, D2)),
        )

        checks.find(NAUSYS, 1, D1, D2)!!.run {
            firstEmptyOn shouldBe null
            priority shouldBe 3
            lastResult shouldBe PeriodCheckResult.EMPTY
        }
        checks.find(NAUSYS, 2, D1, D2)!!.run {
            firstEmptyOn shouldBe null
            hiddenOn shouldBe null
            hiddenRows shouldBe 0
        }
        // a row without a strike or hide is not rewritten, a missing one is not created
        jdbc.queryForObject("SELECT xmin::text FROM partner_period_check WHERE yacht_id = 3", String::class.java) shouldBe xminBefore
        checks.find(NAUSYS, 9, D1, D2) shouldBe null
        checks.find(MMK, 1, D1, D2)!!.firstEmptyOn shouldBe DAY1
        checks.closeStrikesOnQuoteBatch(NAUSYS, emptyList())
    }

    @Test
    fun `a strike needs a negative decided answer`() {
        shouldThrow<IllegalArgumentException> { checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.QUOTED_FREE, DAY1) }
        shouldThrow<IllegalArgumentException> { checks.recordFirstStrike(NAUSYS, 1, D1, D2, 3, PeriodCheckResult.UNKNOWN, DAY1) }
        count("partner_period_check") shouldBe 0
    }

    @Test
    fun `markHidden creates the row when missing`() {
        checks.markHidden(MMK, 2, D2, D3, 1, DAY1)
        checks.find(MMK, 2, D2, D3)!!.run {
            hiddenOn shouldBe DAY1
            hiddenRows shouldBe 1
            priority shouldBe 5
        }
    }

    @Test
    fun `upsertPending creates a row without a result and only lowers the priority`() {
        checks.upsertPending(MMK, 1, D1, D2, 3)
        checks.find(MMK, 1, D1, D2)!!.run {
            priority shouldBe 3
            lastCheckedOn shouldBe null
            lastResult shouldBe null
        }
        checks.recordChecked(MMK, 1, D1, D2, 3, PeriodCheckResult.QUOTED_FREE, today = DAY1)
        checks.upsertPending(MMK, 1, D1, D2, 5)
        checks.upsertPending(MMK, 1, D1, D2, 1)
        checks.find(MMK, 1, D1, D2)!!.run {
            priority shouldBe 1
            lastResult shouldBe PeriodCheckResult.QUOTED_FREE
            lastCheckedOn shouldBe DAY1
        }
    }

    @Test
    fun `findOpenStrikes - open only, per partner, oldest first, limited`() {
        checks.recordFirstStrike(NAUSYS, 1, D2, D3, 5, PeriodCheckResult.EMPTY, DAY2)
        checks.recordFirstStrike(NAUSYS, 2, D1, D2, 3, PeriodCheckResult.EMPTY, DAY1)
        checks.recordFirstStrike(NAUSYS, 1, D1, D2, 5, PeriodCheckResult.EMPTY, DAY1)
        checks.recordFirstStrike(NAUSYS, 3, D1, D2, 5, PeriodCheckResult.EMPTY, DAY1)
        checks.markHidden(NAUSYS, 3, D1, D2, 1, DAY2) // hidden: no longer open
        checks.recordFirstStrike(MMK, 1, D1, D2, 1, PeriodCheckResult.EMPTY, DAY1) // other partner
        checks.recordChecked(NAUSYS, 2, D2, D3, 1, PeriodCheckResult.EMPTY, today = DAY1) // no strike

        checks.findOpenStrikes(NAUSYS, 10).map { Triple(it.yachtId, it.dateFrom, it.firstEmptyOn) } shouldBe
            listOf(Triple(2L, D1, DAY1), Triple(1L, D1, DAY1), Triple(1L, D2, DAY2))
        checks.findOpenStrikes(NAUSYS, 1).size shouldBe 1
        checks.findOpenStrikes(MMK, 10).single().externalSystem shouldBe MMK
    }

    @Test
    fun `batch variants write the same as the single calls`() {
        checks.recordCheckedBatch(
            NAUSYS,
            listOf(
                PeriodCheck(1, D1, D2, 5, PeriodCheckResult.QUOTED_FREE),
                PeriodCheck(2, D1, D2, 4, PeriodCheckResult.QUOTED_OPTION),
                PeriodCheck(1, D1, D2, 2, PeriodCheckResult.UNKNOWN), // same period again in one batch
                PeriodCheck(3, D1, D2, 5, PeriodCheckResult.EMPTY),
            ),
            DAY1,
        )
        count("partner_period_check") shouldBe 3
        checks.find(NAUSYS, 1, D1, D2)!!.run {
            priority shouldBe 2
            lastResult shouldBe PeriodCheckResult.QUOTED_FREE
            lastQuotedOn shouldBe DAY1
        }
        checks.find(NAUSYS, 3, D1, D2)!!.lastQuotedOn shouldBe null

        checks.recordFirstStrikeBatch(
            NAUSYS,
            listOf(PeriodCheck(2, D1, D2, 3, PeriodCheckResult.EMPTY), PeriodCheck(3, D1, D2, 5, PeriodCheckResult.ROUTE_GONE)),
            DAY1,
        )
        checks.recordFirstStrikeBatch(NAUSYS, listOf(PeriodCheck(2, D1, D2, 3, PeriodCheckResult.EMPTY)), DAY2)
        checks.find(NAUSYS, 2, D1, D2)!!.run {
            firstEmptyOn shouldBe DAY1
            lastCheckedOn shouldBe DAY2
            priority shouldBe 3
        }
        checks.find(NAUSYS, 3, D1, D2)!!.lastResult shouldBe PeriodCheckResult.ROUTE_GONE
        shouldThrow<IllegalArgumentException> {
            checks.recordFirstStrikeBatch(NAUSYS, listOf(PeriodCheck(1, D1, D2, 3, PeriodCheckResult.QUOTED_FREE)), DAY1)
        }

        checks.clearStrikeBatch(NAUSYS, listOf(PeriodKey(2, D1, D2), PeriodKey(3, D1, D2), PeriodKey(9, D1, D2)), priority = 5)
        checks.findOpenStrikes(NAUSYS, 10) shouldBe emptyList()
        checks.find(NAUSYS, 2, D1, D2)!!.priority shouldBe 5

        checks.upsertPendingBatch(MMK, listOf(PeriodKey(1, D1, D2), PeriodKey(1, D2, D3), PeriodKey(1, D1, D2)), 3)
        checks.find(MMK, 1, D1, D2)!!.priority shouldBe 3
        checks.find(MMK, 1, D2, D3)!!.lastResult shouldBe null

        // empty lists are no-ops
        checks.recordCheckedBatch(NAUSYS, emptyList(), DAY1)
        checks.recordFirstStrikeBatch(NAUSYS, emptyList(), DAY1)
        checks.clearStrikeBatch(NAUSYS, emptyList())
        checks.upsertPendingBatch(NAUSYS, emptyList(), 1)
    }

    // ---- ReverifyRunStore

    @Test
    fun `run log - start, additive counters, finish`() {
        val id = runs.start(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.LOG)
        runs.find(id)!!.run {
            job shouldBe "NAUSYS_REVERIFY"
            mode shouldBe ReverifyRunMode.LOG
            endedAt shouldBe null
            counters shouldBe ReverifyCounters()
            escalatedAgencyIds shouldBe null
        }

        runs.addCounters(id, ReverifyCounters(calls = 10, quoted = 6, empty = 3, unknown = 1))
        runs.addCounters(id, ReverifyCounters(calls = 5, quoted = 5, upserts = 2))
        runs.finish(
            id,
            ReverifyCounters(firstStrikes = 3, hiddenPeriods = 1, hiddenRows = 2, wouldFlipRows = 4),
            abortedReason = "x".repeat(250),
            escalatedAgencyIds = listOf(12L, 34L),
        )
        runs.find(id)!!.run {
            counters shouldBe
                ReverifyCounters(
                    calls = 15,
                    quoted = 11,
                    empty = 3,
                    unknown = 1,
                    upserts = 2,
                    firstStrikes = 3,
                    hiddenPeriods = 1,
                    hiddenRows = 2,
                    wouldFlipRows = 4,
                )
            endedAt shouldNotBe null
            abortedReason!!.length shouldBe 200
            escalatedAgencyIds shouldBe listOf(12L, 34L)
        }

        // a second finish without reason / ids keeps them
        runs.finish(id)
        runs.find(id)!!.run {
            abortedReason!!.length shouldBe 200
            escalatedAgencyIds shouldBe listOf(12L, 34L)
            counters.calls shouldBe 15
        }
    }

    @Test
    fun `run log - ids are distinct, plain finish leaves reason and ids empty`() {
        val a = runs.start(ReverifyJobs.MMK_OPTION, ReverifyRunMode.WRITE)
        val b = runs.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.LOG)
        (a != b) shouldBe true
        runs.finish(a)
        runs.find(a)!!.run {
            mode shouldBe ReverifyRunMode.WRITE
            abortedReason shouldBe null
            escalatedAgencyIds shouldBe null
        }
        runs.find(b)!!.endedAt shouldBe null
        shouldThrow<IllegalArgumentException> { runs.start("J".repeat(41), ReverifyRunMode.LOG) }
    }

    // ---- RealPriceEvidenceRetentionJob

    @Test
    fun `retention - flip log and runs after 180 days, period checks 30 days after the period`() {
        val today = jdbc.queryForObject("SELECT CURRENT_DATE", LocalDate::class.java)!!
        checks.upsertPending(NAUSYS, 1, today.minusDays(38), today.minusDays(31), 5) // ended 31 days ago: purged
        checks.upsertPending(NAUSYS, 1, today.minusDays(37), today.minusDays(30), 5) // 30 days: kept
        checks.upsertPending(NAUSYS, 1, today.plusDays(10), today.plusDays(17), 5) // future: kept

        fun run(daysAgo: Int): Long =
            jdbc.queryForObject(
                "INSERT INTO reverify_run (job, mode, started_at) VALUES ('NAUSYS_D3', 'WRITE', now() - make_interval(days => ?)) RETURNING id",
                Long::class.java,
                daysAgo,
            )!!

        fun flip(
            runId: Long,
            daysAgo: Int,
        ) = jdbc.update(
            "INSERT INTO partner_offer_flip_log (run_id, job, reason, external_system_id, offer_id, yacht_id, date_from, date_to, " +
                "old_status, old_ext_status, old_client_price, new_status, decided_on, created_at) " +
                "VALUES (?, 'NAUSYS_D3', 'ROUTE_GONE', 2, 500, 1, ?, ?, 'FREE', NULL, 1234.5, 'UNAVAILABLE', ?, now() - make_interval(days => ?))",
            runId,
            today,
            today.plusDays(7),
            today,
            daysAgo,
        )

        val oldRunOldFlip = run(200)
        flip(oldRunOldFlip, 200) // both go
        val oldRunYoungFlip = run(181)
        flip(oldRunYoungFlip, 179) // flip kept, so its run is kept (FK)
        val oldRunNoFlip = run(181) // goes
        val youngRun = run(10)
        flip(youngRun, 10)

        val summary = RealPriceEvidenceRetentionJob(jdbc).purgeOnce()

        summary shouldBe RealPriceEvidenceRetentionJob.Summary(flipLogDeleted = 1, runsDeleted = 2, periodChecksDeleted = 1)
        jdbc.queryForList("SELECT id FROM reverify_run ORDER BY id", Long::class.java) shouldBe listOf(oldRunYoungFlip, youngRun)
        jdbc.queryForList("SELECT run_id FROM partner_offer_flip_log ORDER BY run_id", Long::class.java) shouldBe
            listOf(oldRunYoungFlip, youngRun)
        jdbc.queryForList("SELECT date_to FROM partner_period_check ORDER BY date_to", LocalDate::class.java) shouldBe
            listOf(today.minusDays(30), today.plusDays(17))
        oldRunNoFlip shouldNotBe youngRun

        // a second pass deletes nothing
        RealPriceEvidenceRetentionJob(jdbc).purgeOnce() shouldBe RealPriceEvidenceRetentionJob.Summary(0, 0, 0)
    }
}
