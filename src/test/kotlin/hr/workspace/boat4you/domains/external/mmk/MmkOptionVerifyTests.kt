package hr.workspace.boat4you.domains.external.mmk

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkOptionVerifyMode
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferStatusPrice
import hr.workspace.boat4you.domains.catalouge.jpa.StaleMmkOfferCombo
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyCounters
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.mmk.service.MmkFreeOfferReverifyService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferSyncService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.openapitools.client.mmk.model.Offer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-price B1 (DESIGN.md WS-B): steps 2c/2b, drift-only upserts, the LOG/ON/OFF switch, bookkeeping and the run log
 * of [MmkFreeOfferReverifyService]. The partner is a map (yacht, from, to) -> rows; the strike table is an in-memory
 * map behind a JdbcTemplate mock; the repository and the stores are recording mocks.
 */
class MmkOptionVerifyTests {
    private val day1 = LocalDate.of(2026, 10, 15)
    private val day2 = day1.plusDays(1)
    private val jan2 = LocalDate.of(2027, 1, 2)
    private val june5 = LocalDate.of(2027, 6, 5)

    private data class Key(
        val yacht: Long,
        val from: LocalDate,
        val to: LocalDate,
    )

    // ---- partner
    private val answers = ConcurrentHashMap<Key, List<Offer>>()
    private val failing = ConcurrentHashMap.newKeySet<Long>()
    private val calls = CopyOnWriteArrayList<Key>()

    private fun mmk(
        yacht: Long,
        from: LocalDate,
        to: LocalDate,
        status: Int = 0,
        price: Double = 1000.0,
    ) = Offer(
        yachtId = yacht * 1000,
        yacht = "Y$yacht",
        startBaseId = 1,
        endBaseId = 1,
        startBase = "A",
        endBase = "A",
        dateFrom = MmkDateTimeWrapper(LocalDateTime.of(from, LocalTime.MIN).format(MmkDateTimeWrapper.READ_FORMATTER)),
        dateTo = MmkDateTimeWrapper(LocalDateTime.of(to, LocalTime.MIN).format(MmkDateTimeWrapper.READ_FORMATTER)),
        product = "Bareboat",
        price = price,
        currency = "EUR",
        startPrice = price,
        discountPercentage = 0f,
        status = status,
    )

    private val client =
        mock(MmkRetryableClient::class.java) { inv ->
            if (inv.method.name != "getOffers") return@mock null
            val ext = (inv.arguments.first { it is List<*> && (it as List<*>).firstOrNull() is Long } as List<*>).single() as Long
            val from = (inv.arguments[0] as MmkDateTimeWrapper).value!!.toLocalDate()
            val to = (inv.arguments[1] as MmkDateTimeWrapper).value!!.toLocalDate()
            val key = Key(ext / 1000, from, to)
            calls += key
            if (ext / 1000 in failing) error("MMK 503")
            answers[key] ?: emptyList<Offer>()
        }

    // ---- repository
    private var freeCombos: List<StaleMmkOfferCombo> = emptyList()
    private val staleOptions = ConcurrentHashMap<LocalDate, List<StaleMmkOfferCombo>>()
    private val staleOptionArgs = CopyOnWriteArrayList<List<Any?>>()
    private val stored = ConcurrentHashMap<Key, List<OfferStatusPrice>>()
    private val storedReads = CopyOnWriteArrayList<Key>()
    private val marks = CopyOnWriteArrayList<List<Any?>>()
    private val counts = CopyOnWriteArrayList<List<Any?>>()
    private var rowsPerPeriod = 2

    private fun combo(
        yacht: Long,
        start: LocalDate,
        nights: Long = 7,
        agency: Long = 1,
    ) = object : StaleMmkOfferCombo {
        override val yachtId = yacht
        override val externalYachtId = yacht * 1000
        override val agencyId = agency
        override val dateFrom = start
        override val dateTo = start.plusDays(nights)
    }

    private fun storedRow(
        status: String,
        price: String?,
    ) = object : OfferStatusPrice {
        override val status = status
        override val extTotalPrice = price?.let(::BigDecimal)
    }

    private val offers =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findShownFreeMmkCombos" -> freeCombos
                "findStaleMmkOptionPeriods" -> {
                    staleOptionArgs += inv.arguments.toList()
                    staleOptions[inv.getArgument(1)] ?: emptyList<StaleMmkOfferCombo>()
                }
                "findOpenMmkStrikePeriods" -> {
                    val today = inv.getArgument<LocalDate>(1)
                    strikes.filter { (_, v) -> v.second == null && v.first.isBefore(today) }.keys.map { k ->
                        val (y, f, t) = k.split('|')
                        combo(y.toLong(), LocalDate.parse(f), LocalDate.parse(t).toEpochDay() - LocalDate.parse(f).toEpochDay())
                    }
                }
                "findPeriodStatusPrices" -> {
                    val k = Key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))
                    storedReads += k
                    stored[k] ?: listOf(storedRow("FREE", "1000"))
                }
                "markMmkPeriodUnavailable" -> {
                    marks += inv.arguments.toList()
                    rowsPerPeriod
                }
                "countMmkPeriodFlippable" -> {
                    counts += inv.arguments.toList()
                    rowsPerPeriod
                }
                else -> null
            }
        }

    // ---- lock: records whether the sync service is ever called inside runExclusiveYachtWrite
    private val inLock = ThreadLocal.withInitial { false }
    private val syncCalls = CopyOnWriteArrayList<Pair<List<Offer>, Boolean>>()
    private val mutex =
        mock(YachtSyncMutex::class.java) { inv ->
            if (inv.method.name == "runExclusiveYachtWrite") {
                inLock.set(true)
                try {
                    (inv.getArgument<() -> Unit>(1)).invoke()
                } finally {
                    inLock.set(false)
                }
                true
            } else {
                null
            }
        }
    private val sync =
        mock(MmkYachtOfferSyncService::class.java) { inv ->
            if (inv.method.name == "syncOffers") syncCalls += (inv.getArgument<List<Offer>>(0) to inLock.get())
            null
        }

    // ---- strike table "yacht|from|to" -> (firstEmptyOn, hiddenOn)
    private val strikes = ConcurrentHashMap<String, Pair<LocalDate, LocalDate?>>()
    private val jdbc =
        mock(JdbcTemplate::class.java) { inv ->
            val sql = inv.arguments[0] as String
            when {
                sql.startsWith("SELECT first_empty_on") -> {
                    val s = strikes["${inv.arguments[2]}|${inv.arguments[3]}|${inv.arguments[4]}"]
                    if (s == null || s.second != null) emptyList<LocalDate>() else listOf(s.first)
                }
                sql.startsWith("INSERT INTO mmk_free_week_strike") -> {
                    val a = inv.arguments.drop(1)
                    strikes.compute("${a[0]}|${a[1]}|${a[2]}") { _, old -> if (old == null || old.second != null) (a[3] as LocalDate) to null else old }
                    1
                }
                sql.startsWith("UPDATE mmk_free_week_strike") -> {
                    val a = inv.arguments.drop(1)
                    val k = "${a[2]}|${a[3]}|${a[4]}"
                    strikes[k] = strikes[k]!!.first to (a[0] as LocalDate)
                    1
                }
                sql.startsWith("DELETE FROM mmk_free_week_strike") -> {
                    val a = inv.arguments.drop(1)
                    strikes.remove("${a[0]}|${a[1]}|${a[2]}")
                    1
                }
                else -> if (inv.arguments.getOrNull(1) is RowMapper<*>) emptyList<Any>() else 0
            }
        }

    // ---- stores
    private val bookkeeping = CopyOnWriteArrayList<PeriodCheck>()
    private val checks =
        mock(PartnerPeriodCheckStore::class.java) { inv ->
            if (inv.method.name == "recordCheckedBatch") bookkeeping += inv.getArgument<List<PeriodCheck>>(1)
            null
        }
    private val runIds = AtomicLong(100)
    private val started = CopyOnWriteArrayList<Pair<String, ReverifyRunMode>>()
    private val finished = ConcurrentHashMap<Long, ReverifyCounters>()
    private val flushes = CopyOnWriteArrayList<Long>()
    private val runs =
        mock(ReverifyRunStore::class.java) { inv ->
            when (inv.method.name) {
                "start" -> {
                    started += inv.getArgument<String>(0) to inv.getArgument<ReverifyRunMode>(1)
                    runIds.incrementAndGet()
                }
                "addCounters" -> {
                    val id = inv.getArgument<Long>(0)
                    flushes += id
                    finished.merge(id, inv.getArgument(1), ReverifyCounters::plus)
                    null
                }
                "finish" -> {
                    finished.merge(inv.getArgument(0), inv.getArgument(1), ReverifyCounters::plus)
                    null
                }
                else -> null
            }
        }

    private var mode = MmkOptionVerifyMode.ON
    private val switches = mock(RealPriceSwitches::class.java).also { s -> `when`(s.mmkOptionVerify).thenAnswer { mode } }

    private val service = MmkFreeOfferReverifyService(offers, client, mutex, jdbc, sync, checks, runs, switches)

    /** 60 yachts whose weeks MMK quotes at the stored price: the probe breakers stay quiet. */
    private fun healthyFleet(): List<StaleMmkOfferCombo> =
        (100L until 160L).flatMap { y ->
            (0L until 3L).map { w ->
                val start = jan2.plusWeeks(w)
                answers[Key(y, start, start.plusDays(7))] = listOf(mmk(y, start, start.plusDays(7)))
                combo(y, start, agency = 900 + y)
            }
        }

    private fun stale(
        yacht: Long,
        start: LocalDate,
        on: LocalDate,
    ) {
        staleOptions[on] = (staleOptions[on] ?: emptyList()) + combo(yacht, start)
    }

    private fun quote(
        yacht: Long,
        start: LocalDate,
        status: Int = 0,
        price: Double = 1000.0,
    ) {
        answers[Key(yacht, start, start.plusDays(7))] = listOf(mmk(yacht, start, start.plusDays(7), status, price))
    }

    /** Run ids are handed out 101, 102, ... in start order; the latest MMK_OPTION run. */
    private fun lastOptionRunId() = started.indexOfLast { it.first == ReverifyJobs.MMK_OPTION }.takeIf { it >= 0 }?.let { 101L + it }

    // ---- quotes

    @Test
    fun `a quoted stale option in step 2c is upserted through syncOffers outside any outer lock and clears its strike`() {
        freeCombos = healthyFleet()
        stale(1, june5, day1)
        quote(1, june5, status = 0, price = 4200.0)
        strikes["1|$june5|${june5.plusDays(7)}"] = day1.minusDays(3) to null

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldBeNull()
        val upsert = syncCalls.single()
        upsert.first.single().price shouldBe 4200.0
        upsert.second shouldBe false // never nested in runExclusiveYachtWrite: its try-lock would fail and drop the quote
        strikes["1|$june5|${june5.plusDays(7)}"].shouldBeNull()
        r.upserts shouldBe 1
        bookkeeping.single { it.yachtId == 1L }.let {
            it.result shouldBe PeriodCheckResult.QUOTED_FREE
            it.priority shouldBe PeriodCheckPriority.STALE_OPTION
        }
        marks.shouldBeEmpty()
    }

    @Test
    fun `a quoted UNDER_OPTION is upserted with the partner's own status and never hidden`() {
        freeCombos = healthyFleet()
        stale(2, june5, day1)
        quote(2, june5, status = 9) // MMK 9 = under option -> OfferStatus.OPTION_WAITING in the sync

        service.reverifyFreeOffers(day1)

        syncCalls.single().first.single().status shouldBe 9
        bookkeeping.single { it.yachtId == 2L }.result shouldBe PeriodCheckResult.QUOTED_OPTION
        marks.shouldBeEmpty()
        strikes.keys.none { it.startsWith("2|") } shouldBe true
    }

    @Test
    fun `an identical quote outside steps 2c and 2b does not call syncOffers`() {
        freeCombos = healthyFleet()

        service.reverifyFreeOffers(day1)

        storedReads.isEmpty() shouldBe false // the drift check ran for the quoted samples
        syncCalls.shouldBeEmpty()
    }

    @Test
    fun `a drifted price above 1 percent is upserted, a 0_5 percent difference is not`() {
        freeCombos = healthyFleet()
        quote(100, jan2, price = 1020.0) // first sample of yacht 100: +2 %
        quote(101, jan2, price = 1005.0) // +0.5 %

        val r = service.reverifyFreeOffers(day1)

        syncCalls.map { it.first.single().yachtId } shouldContainExactly listOf(100_000L)
        syncCalls.single().second shouldBe false
        r.upserts shouldBe 1
    }

    @Test
    fun `a quoted status that differs from the stored row is drift`() {
        freeCombos = healthyFleet()
        stored[Key(102, jan2, jan2.plusDays(7))] = listOf(storedRow("OPTION", "1000"))

        service.reverifyFreeOffers(day1)

        syncCalls.map { it.first.single().yachtId } shouldContainExactly listOf(102_000L)
    }

    // ---- two strikes

    @Test
    fun `an OPTION_WAITING period without a live hold is hidden on day 2 only, exact dates, through step 2b`() {
        freeCombos = healthyFleet()
        stale(3, june5, day1) // day 2: the rotation skips it (checked yesterday); step 2b re-asks the open strike

        val r1 = service.reverifyFreeOffers(day1)
        r1.optionFirstStrikes shouldBe 1
        marks.shouldBeEmpty()

        val r2 = service.reverifyFreeOffers(day2)

        r2.optionHiddenPeriods shouldBe 1
        val m = marks.single()
        m.subList(0, 3) shouldBe listOf(3L, june5, june5.plusDays(7))
        m[3] shouldBe lastOptionRunId()
        m[4] shouldBe ReverifyJobs.MMK_OPTION
        m[5] shouldBe MmkFreeOfferReverifyService.REASON_TWO_STRIKE_EMPTY
        m[6] shouldBe day1
        m[7] shouldBe day2
        m[8] shouldBe true // OPTION_WAITING in the write scope only with the switch ON
        strikes["3|$june5|${june5.plusDays(7)}"]!!.second shouldBe day2
        calls.count { it == Key(3, june5, june5.plusDays(7)) } shouldBe 2
    }

    @Test
    fun `LOG mode probes, records bookkeeping and strikes, but writes no offer row and counts the would-flips`() {
        mode = MmkOptionVerifyMode.LOG
        freeCombos = healthyFleet()
        stale(4, june5, day1)
        stale(5, june5, day1)
        quote(5, june5, price = 3000.0)
        quote(100, jan2, price = 1500.0) // drift in a step-1 sample

        val r1 = service.reverifyFreeOffers(day1)
        val r2 = service.reverifyFreeOffers(day2)

        syncCalls.shouldBeEmpty()
        marks.shouldBeEmpty()
        strikes["4|$june5|${june5.plusDays(7)}"]!!.second.shouldBeNull() // struck, never hidden
        counts.single().let {
            it.subList(0, 3) shouldBe listOf(4L, june5, june5.plusDays(7))
            it[3] shouldBe true
        }
        r2.wouldFlipRows shouldBe 2
        started.filter { it.first == ReverifyJobs.MMK_OPTION }.map { it.second }.toSet() shouldBe setOf(ReverifyRunMode.LOG)
        // day 1: the 2c quote of yacht 5 + the drifted step-1 sample = 2 would-upserts, written to the LOG run
        r1.upserts shouldBe 2
        finished[r1.optionRunId!!]!!.upserts shouldBe 2
        finished[r2.optionRunId!!]!!.wouldFlipRows shouldBe 2
    }

    @Test
    fun `step 2 hides only FREE and unbacked OPTION rows unless the switch is ON`() {
        mode = MmkOptionVerifyMode.LOG
        freeCombos = healthyFleet() + (0L until 3L).map { combo(6, june5.plusWeeks(it)) }

        service.reverifyFreeOffers(day1)
        service.reverifyFreeOffers(day2)

        marks.size shouldBe 3
        marks.all { it[8] == false && it[4] == ReverifyJobs.MMK_FREE } shouldBe true
    }

    @Test
    fun `step 2c asks for at most 3000 periods, skipping those checked since yesterday`() {
        freeCombos = healthyFleet()

        service.reverifyFreeOffers(day1)

        staleOptionArgs.single() shouldBe listOf(1L, day1, day1.minusDays(1), MmkFreeOfferReverifyService.MMK_OPTION_VERIFY_MAX_PER_RUN)
        MmkFreeOfferReverifyService.MMK_OPTION_VERIFY_MAX_PER_RUN shouldBe 3000
    }

    @Test
    fun `step 2b re-asks an on-demand strike and a quote clears it`() {
        freeCombos = healthyFleet()
        strikes["7|$june5|${june5.plusDays(7)}"] = day1.minusDays(1) to null // written by the on-demand path yesterday
        quote(7, june5)

        service.reverifyFreeOffers(day1)

        calls.count { it.yacht == 7L } shouldBe 1
        strikes["7|$june5|${june5.plusDays(7)}"].shouldBeNull()
        syncCalls.single().second shouldBe false
        bookkeeping.single { it.yachtId == 7L }.priority shouldBe PeriodCheckPriority.ON_DEMAND_OR_BOOKING
    }

    @Test
    fun `an agency the probe breakers marked suspect gets bookkeeping but no strike in step 2c`() {
        freeCombos = healthyFleet() + (1L..6L).map { y -> combo(10 + y, jan2, agency = 77) } // 6 of 6 week groups empty
        stale(20, june5, day1)
        staleOptions[day1] = staleOptions[day1]!!.map { combo(it.yachtId, it.dateFrom, agency = 77) }

        service.reverifyFreeOffers(day1)

        bookkeeping.single { it.yachtId == 20L }.result shouldBe PeriodCheckResult.EMPTY
        strikes.keys.none { it.startsWith("20|") } shouldBe true
    }

    // ---- bookkeeping, run log, OFF regression

    @Test
    fun `every probe answer is recorded, unknown ones as UNKNOWN`() {
        freeCombos = healthyFleet()
        stale(8, june5, day1)
        failing += 8

        service.reverifyFreeOffers(day1)

        bookkeeping.size shouldBe calls.size
        bookkeeping.single { it.yachtId == 8L }.result shouldBe PeriodCheckResult.UNKNOWN
        bookkeeping.count { it.result == PeriodCheckResult.QUOTED_FREE } shouldBe 60
    }

    @Test
    fun `switch OFF behaves as today plus bookkeeping - no option steps, no drift reads, no upserts`() {
        mode = MmkOptionVerifyMode.OFF
        freeCombos = healthyFleet()
        stale(9, june5, day1)
        quote(100, jan2, price = 2000.0)
        strikes["9|$june5|${june5.plusDays(7)}"] = day1.minusDays(1) to null

        val r = service.reverifyFreeOffers(day1)

        r.optionRunId.shouldBeNull()
        started shouldContainExactly listOf(ReverifyJobs.MMK_FREE to ReverifyRunMode.WRITE)
        staleOptionArgs.shouldBeEmpty()
        storedReads.shouldBeEmpty()
        syncCalls.shouldBeEmpty()
        calls.none { it.yacht == 9L } shouldBe true
        bookkeeping.size shouldBe calls.size
        finished[r.freeRunId!!]!!.calls shouldBe calls.size
    }

    @Test
    fun `the probe breakers stop steps 2c and 2b`() {
        freeCombos = (1L..20L).map { combo(it, jan2, agency = 500 + it) } // 20 groups, all empty: too few decided
        stale(9, june5, day1)

        val r = service.reverifyFreeOffers(day1)

        r.aborted.shouldNotBeNull()
        staleOptionArgs.shouldBeEmpty()
    }

    @Test
    fun `the heartbeat flushes the run counters while the run is still working`() {
        service.heartbeatEvery = Duration.ofMillis(20)
        freeCombos = healthyFleet()

        service.reverifyFreeOffers(day1)

        flushes.size shouldBeGreaterThan 0
    }
}
