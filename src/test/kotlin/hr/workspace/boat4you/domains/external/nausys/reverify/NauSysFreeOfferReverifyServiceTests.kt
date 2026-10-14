package hr.workspace.boat4you.domains.external.nausys.reverify

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.NauSysPeriod
import hr.workspace.boat4you.domains.catalouge.jpa.NauSysVerifyCandidate
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyCounters
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.nausys.service.NauSysFreeOfferReverifyService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysReverifyLimits
import hr.workspace.boat4you.domains.external.nausys.service.NauSysVerifyClass
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferSyncService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.openapitools.client.nausys.model.RestYachtReservationPriceInfo
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-price B2 (DESIGN.md WS-B2): [NauSysFreeOfferReverifyService]. The partner is a map (NauSys yacht, from, to) ->
 * rows behind a client mock; the candidate query, the write queries and the evidence stores are recording mocks (the
 * SQL itself is NauSysFlipLogIntegrationTest's job).
 */
class NauSysFreeOfferReverifyServiceTests {
    private val day1 = LocalDate.of(2026, 10, 15)
    private val day2 = day1.plusDays(1)
    private val may1 = LocalDate.of(2027, 5, 1) // a Saturday

    private fun week(i: Int): LocalDate = may1.plusWeeks(i.toLong())

    private data class Key(
        val ext: Long,
        val from: LocalDate,
        val to: LocalDate,
    )

    private fun ext(yacht: Long) = 50_000 + yacht

    // ---- partner
    private val answers = ConcurrentHashMap<Key, List<RestFreeYacht>>()
    private val notOkAlone = ConcurrentHashMap.newKeySet<Long>()
    private var callDelayMs = 0L
    private val rateLimited = ConcurrentHashMap.newKeySet<Long>()
    private val requests = CopyOnWriteArrayList<RestFreeYachtsRequest>()

    private fun row(
        yacht: Long,
        from: LocalDate,
        nights: Long = 7,
        status: String = "FREE",
        price: String? = "1000.00",
        locFrom: Long = 900,
        locTo: Long = 900,
    ) = RestFreeYacht(
        periodFrom = NauSysDateWrapper(from.format(NauSysDateWrapper.DATE_FORMATTER)),
        periodTo = NauSysDateWrapper(from.plusDays(nights).format(NauSysDateWrapper.DATE_FORMATTER)),
        yachtId = ext(yacht),
        locationFromId = locFrom,
        locationToId = locTo,
        price = RestYachtReservationPriceInfo(clientPrice = price),
        status = status,
    )

    private fun quote(
        yacht: Long,
        from: LocalDate,
        vararg rows: RestFreeYacht,
        nights: Long = 7,
    ) {
        answers[Key(ext(yacht), from, from.plusDays(nights))] = rows.toList().ifEmpty { listOf(row(yacht, from, nights)) }
    }

    private val client =
        mock(NauSysRetryableClient::class.java) { inv ->
            if (inv.method.name != "getFreeYachts") return@mock null
            val req = inv.arguments[0] as RestFreeYachtsRequest
            requests += req
            val from = req.periodFrom.value!!
            val to = req.periodTo.value!!
            val yachts = req.yachts.orEmpty()
            if (yachts.any { it in rateLimited }) throw NauSysRateLimitedException("/freeYachts", 6)
            if (callDelayMs > 0) Thread.sleep(callDelayMs)
            if (yachts.size == 1 && yachts.single() in notOkAlone) return@mock RestFreeYachtList(status = "ERROR", errorCode = java.math.BigDecimal(17))
            RestFreeYachtList(status = "OK", freeYachts = yachts.flatMap { answers[Key(it, from, to)].orEmpty() })
        }

    // ---- candidates and repository
    private val candidates = CopyOnWriteArrayList<NauSysVerifyCandidate>()
    private val occupied = CopyOnWriteArrayList<NauSysPeriod>()
    private val marks = CopyOnWriteArrayList<List<Any?>>()
    private val counts = CopyOnWriteArrayList<List<Any?>>()
    private var rowsPerFlip = 2

    @Suppress("LongParameterList")
    private fun cand(
        yacht: Long,
        from: LocalDate,
        cls: NauSysVerifyClass,
        agency: Long = yacht / 100,
        nights: Long = 7,
        minDuration: Int? = null,
        shown: String? = "${yacht * 10}:10>10",
    ) {
        candidates +=
            object : NauSysVerifyCandidate {
                override val yachtId = yacht
                override val externalYachtId = ext(yacht)
                override val agencyId = agency
                override val dateFrom = from
                override val dateTo = from.plusDays(nights)
                override val cls = cls.code
                override val seasonMinDuration = minDuration
                override val shownRows = shown
            }
    }

    /** [agencies] x [weeks] grid-sample groups (one yacht per agency, agency*100+1), all quoted unless listed empty. */
    private fun baseline(
        agencies: IntRange = 1..6,
        weeks: Int = 10,
        empty: Set<Pair<Int, Int>> = emptySet(),
    ) {
        for (a in agencies) {
            for (w in 0 until weeks) {
                val yacht = a * 100L + 1
                cand(yacht, week(w), NauSysVerifyClass.P5)
                if ((a to w) !in empty) quote(yacht, week(w))
            }
        }
    }

    private val offers =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findNauSysVerifyCandidates" -> candidates.toList()
                "findNauSysOccupiedShownPeriods" -> occupied.toList()
                "markNauSysPeriodUnavailable" -> {
                    marks += inv.arguments.toList()
                    rowsPerFlip
                }
                "countNauSysPeriodFlippable" -> {
                    counts += inv.arguments.toList()
                    rowsPerFlip
                }
                else -> null
            }
        }

    // ---- evidence stores
    private data class StrikeKey(
        val yacht: Long,
        val from: LocalDate,
    )

    private val strikes = ConcurrentHashMap<StrikeKey, LocalDate>()
    private val strikeCalls = CopyOnWriteArrayList<List<Any?>>()
    private val checked = CopyOnWriteArrayList<PeriodCheck>()
    private val cleared = CopyOnWriteArrayList<List<Any?>>()
    private val hiddenCalls = CopyOnWriteArrayList<List<Any?>>()

    private val periodChecks =
        mock(PartnerPeriodCheckStore::class.java) { inv ->
            when (inv.method.name) {
                "recordFirstStrike" -> {
                    strikeCalls += inv.arguments.toList()
                    val k = StrikeKey(inv.getArgument(1), inv.getArgument(2))
                    strikes.getOrPut(k) { inv.getArgument(6) }
                }
                "recordCheckedBatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    checked += inv.getArgument<List<PeriodCheck>>(1)
                    null
                }
                "clearStrike" -> {
                    cleared += inv.arguments.toList()
                    strikes.remove(StrikeKey(inv.getArgument(1), inv.getArgument(2)))
                    1
                }
                "markHidden" -> {
                    hiddenCalls += inv.arguments.toList()
                    null
                }
                else -> null
            }
        }

    private val finished = CopyOnWriteArrayList<List<Any?>>()
    private val started = CopyOnWriteArrayList<List<Any?>>()
    private val runStore =
        mock(ReverifyRunStore::class.java) { inv ->
            when (inv.method.name) {
                "start" -> {
                    started += inv.arguments.toList()
                    77L
                }
                "finish" -> {
                    finished += inv.arguments.toList()
                    null
                }
                else -> null
            }
        }

    private val upserts = CopyOnWriteArrayList<List<RestFreeYacht>>()
    private val offerSync =
        mock(NauSysYachtOfferSyncService::class.java) { inv ->
            if (inv.method.name == "syncOffersForAsync") {
                @Suppress("UNCHECKED_CAST")
                upserts += inv.arguments[0] as List<RestFreeYacht>
            }
            null
        }

    private val mutex =
        mock(YachtSyncMutex::class.java) { inv ->
            if (inv.method.name == "runExclusiveYachtWrite") {
                @Suppress("UNCHECKED_CAST")
                (inv.arguments[1] as () -> Unit).invoke()
                true
            } else {
                null
            }
        }

    private val nausysSystem = ExternalSystem().apply { id = 2 }
    private val mappings =
        mock(ExternalMappingService::class.java).also {
            `when`(it.getCachedAllMappingsByType("Location", nausysSystem)).thenReturn(
                listOf(
                    ExternalMapping(900L, 10L, "Location", nausysSystem, null),
                    ExternalMapping(901L, 11L, "Location", nausysSystem, null),
                ),
            )
        }
    private val systems = mock(ExternalSystemService::class.java).also { `when`(it.findById(2L)).thenReturn(nausysSystem) }

    private var mode = NauSysReverifyMode.WRITE
    private val switches = mock(RealPriceSwitches::class.java) { inv -> if (inv.method.name == "getNausysReverify") mode else null }
    private val limits = NauSysReverifyLimits()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private var now = 0L

    private val service =
        NauSysFreeOfferReverifyService(
            offers, client, NauSysAuthProvider("u", "p"), offerSync, mappings, systems, mutex, periodChecks, runStore, switches, limits,
        ).apply {
            sleeper = { sleeps += it }
            clock = { now }
        }

    private val farDeadline = Long.MAX_VALUE

    // ---- log capture
    private val logger = LoggerFactory.getLogger(NauSysFreeOfferReverifyService::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun logLines() = appender.list.map { it.formattedMessage }

    private fun strikesFor(yacht: Long) = strikeCalls.filter { it[1] == yacht }

    private fun callsFor(yacht: Long) = requests.filter { ext(yacht) in it.yachts.orEmpty() }

    // ---- tests

    @Test
    fun `OFF does nothing - no run row, no call`() {
        mode = NauSysReverifyMode.OFF
        baseline()

        val r = service.reverify(farDeadline, day1)

        r.runId.shouldBeNull()
        requests.shouldBeEmpty()
        started.shouldBeEmpty()
    }

    @Test
    fun `one grouped call per agency and period, a quoted yacht is upserted, an absent one is confirmed alone and struck`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P1, shown = "1020:10>10")

        val r = service.reverify(farDeadline, day1)

        r.aborted.shouldBeNull()
        val week0 = requests.filter { it.periodFrom.value == week(0) && ext(101) in it.yachts.orEmpty() }
        week0.single().yachts!!.sorted() shouldBe listOf(ext(101), ext(102))
        week0.single().ignoreOptions shouldBe true
        week0.single().extendedDataSet shouldBe "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS"
        callsFor(102).map { it.yachts!!.sorted() } shouldBe listOf(listOf(ext(101), ext(102)), listOf(ext(102)))
        upserts.flatten().any { it.yachtId == ext(101) && it.periodFrom!!.value == week(0) } shouldBe true
        upserts.flatten().none { it.yachtId == ext(102) } shouldBe true
        strikesFor(102).single()[5] shouldBe PeriodCheckResult.EMPTY
        marks.shouldBeEmpty()
        r.firstStrikes shouldBe 1
        r.runId shouldBe 77L
        started.single() shouldBe listOf(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.WRITE)
    }

    @Test
    fun `first strike day 1, hide on day 2 for exactly those dates`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P1, shown = "1020:10>10")

        service.reverify(farDeadline, day1)
        marks.shouldBeEmpty()

        service.reverify(farDeadline, day2)

        val m = marks.single()
        m shouldBe listOf(102L, week(0), week(0).plusDays(7), null, false, 77L, ReverifyJobs.NAUSYS_REVERIFY, "TWO_STRIKE_EMPTY", day1, day2)
        hiddenCalls.single().subList(1, 5) shouldBe listOf(102L, week(0), week(0).plusDays(7), 2)
    }

    @Test
    fun `a non-OK answer is unknown - no strike, bookkeeping UNKNOWN only`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P1)
        // the group answers OK (yacht 102 absent), the single-yacht confirmation answers status=ERROR
        notOkAlone += ext(102)

        val r = service.reverify(farDeadline, day1)

        callsFor(102).size shouldBe 2
        strikesFor(102).shouldBeEmpty()
        marks.shouldBeEmpty()
        checked.filter { it.yachtId == 102L }.map { it.result } shouldContainExactly listOf(PeriodCheckResult.UNKNOWN)
        r.unknown shouldBe 1
    }

    @Test
    fun `rate limits - three in a row pause 5 minutes, ten in a run stop it, no own retries`() {
        baseline()
        (1..12).forEach { i ->
            cand(5000L + i, week(20 + i), NauSysVerifyClass.P4, agency = 50)
            rateLimited += ext(5000L + i)
        }

        val r = service.reverify(farDeadline, day1)

        // all in step 1 (one yacht per group): the run stops there, nothing is struck
        sleeps shouldBe listOf(Duration.ofMinutes(5).toMillis(), Duration.ofMinutes(5).toMillis(), Duration.ofMinutes(5).toMillis())
        r.aborted.shouldNotBeNull() shouldContain "rate limits"
        requests.count { it.yachts.orEmpty().any { y -> y in rateLimited } } shouldBe 10
        strikeCalls.shouldBeEmpty()
    }

    @Test
    fun `LOG mode probes, records strikes and counts, never upserts or flips`() {
        mode = NauSysReverifyMode.LOG
        baseline()
        cand(102, week(0), NauSysVerifyClass.P1)

        service.reverify(farDeadline, day1)
        val r = service.reverify(farDeadline, day2)

        upserts.shouldBeEmpty()
        marks.shouldBeEmpty()
        strikesFor(102).size shouldBe 2
        counts.single() shouldBe listOf(102L, week(0), week(0).plusDays(7), null, false)
        r.wouldFlipRows shouldBe 2
        r.upserts shouldBeGreaterThan 0
        started.last() shouldBe listOf(ReverifyJobs.NAUSYS_REVERIFY, ReverifyRunMode.LOG)
    }

    @Test
    fun `our SYNTHETIC_DISAPPEARANCE marker quoted with a price is upserted from the partner's FREE row and its strike cleared`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P1)
        quote(102, week(0))

        service.reverify(farDeadline, day1)

        upserts.flatten().single { it.yachtId == ext(102) }.status shouldBe "FREE"
        cleared.any { it[1] == 102L && it[4] == PeriodCheckPriority.SAMPLE } shouldBe true
        strikesFor(102).shouldBeEmpty()
        checked.single { it.yachtId == 102L }.result shouldBe PeriodCheckResult.QUOTED_FREE
    }

    @Test
    fun `P2b - an empty answer is recorded but never struck or flipped, a quote keeps the partner's own status`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P2B)
        cand(103, week(1), NauSysVerifyClass.P2B, agency = 1)
        quote(103, week(1), row(103, week(1), status = "UNDER_OPTION", price = null))

        service.reverify(farDeadline, day2)

        callsFor(102).size shouldBe 1 // the grouped call only, no confirmation
        strikesFor(102).shouldBeEmpty()
        checked.single { it.yachtId == 102L }.result shouldBe PeriodCheckResult.EMPTY
        upserts.flatten().single { it.yachtId == ext(103) }.status shouldBe "UNDER_OPTION"
        checked.single { it.yachtId == 103L }.result shouldBe PeriodCheckResult.QUOTED_OPTION
        marks.shouldBeEmpty()
    }

    @Test
    fun `P6 - a stale UNAVAILABLE period quoted FREE with a price is upserted, an empty one changes nothing`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P6, shown = null)
        cand(103, week(1), NauSysVerifyClass.P6, agency = 1, shown = null)
        quote(102, week(0))

        service.reverify(farDeadline, day2)

        upserts.flatten().single { it.yachtId == ext(102) }.price!!.clientPrice shouldBe "1000.00"
        strikeCalls.shouldBeEmpty()
        marks.shouldBeEmpty()
        checked.single { it.yachtId == 103L }.result shouldBe PeriodCheckResult.EMPTY
    }

    @Test
    fun `route gone - the quoted route is upserted, the stale route row is struck and on day 2 flipped alone`() {
        baseline()
        cand(120, week(3), NauSysVerifyClass.P4, agency = 1, shown = "1201:10>10,1202:11>11")
        quote(120, week(3), row(120, week(3), locFrom = 900, locTo = 900))

        service.reverify(farDeadline, day1)
        strikesFor(120).single()[5] shouldBe PeriodCheckResult.ROUTE_GONE
        upserts.flatten().any { it.yachtId == ext(120) } shouldBe true

        service.reverify(farDeadline, day2)
        marks.single() shouldBe listOf(120L, week(3), week(3).plusDays(7), 1202L, false, 77L, ReverifyJobs.NAUSYS_REVERIFY, "ROUTE_GONE", day1, day2)
    }

    @Test
    fun `a FREE answer without a price is an UNPRICED strike`() {
        baseline()
        cand(102, week(0), NauSysVerifyClass.P4)
        quote(102, week(0), row(102, week(0), price = null))

        service.reverify(farDeadline, day1)
        service.reverify(farDeadline, day2)

        strikesFor(102).first()[5] shouldBe PeriodCheckResult.UNPRICED
        marks.single()[7] shouldBe "UNPRICED_TWO_DAY"
    }

    @Test
    fun `MINSTAY empties stay out of the breakers and are struck as MINSTAY`() {
        baseline()
        // 20 empty 7-night grid samples inside a 14-night season: without the exclusion 20 of 80 groups (25 %) would abort
        (0 until 20).forEach { w -> cand(901L, week(20 + w), NauSysVerifyClass.P5, agency = 9, minDuration = 14) }

        val r = service.reverify(farDeadline, day1)

        r.aborted.shouldBeNull()
        r.escalatedAgencyIds.shouldBeEmpty()
        strikesFor(901).map { it[5] }.toSet() shouldBe setOf(PeriodCheckResult.MINSTAY)
    }

    @Test
    fun `an agency with more than half of its baseline groups empty is escalated, not struck`() {
        baseline(agencies = 1..6, weeks = 10)
        (0 until 6).forEach { w ->
            cand(701L, week(w), NauSysVerifyClass.P5, agency = 7)
            if (w >= 4) quote(701L, week(w))
        }

        val r = service.reverify(farDeadline, day1)

        r.aborted.shouldBeNull()
        r.escalatedAgencyIds shouldBe setOf(7L)
        strikesFor(701).shouldBeEmpty()
        callsFor(701).size shouldBe 6 // grouped calls only, no confirmations
        finished.single()[3] shouldBe listOf(7L)
        logLines().any { it.contains("agency 7 has 4 of 6 baseline groups empty") } shouldBe true
    }

    @Test
    fun `a fleet empty share above 10 % aborts before any strike`() {
        baseline(empty = (1..6).flatMap { a -> listOf(a to 0, a to 1) }.toSet()) // 12 of 60 = 20 %

        val r = service.reverify(farDeadline, day1)

        r.aborted.shouldNotBeNull() shouldContain "NauSys outage"
        strikeCalls.shouldBeEmpty()
        requests.size shouldBe 60 // no confirmations
        (finished.single()[2] as String) shouldContain "baseline"
    }

    @Test
    fun `fewer than 50 decided groups abort`() {
        baseline(agencies = 1..4, weeks = 10)
        cand(102, week(0), NauSysVerifyClass.P1)

        val r = service.reverify(farDeadline, day1)

        r.aborted.shouldNotBeNull() shouldContain "only 40"
        strikeCalls.shouldBeEmpty()
    }

    @Test
    fun `the hard deadline stops the run before the next call`() {
        baseline()
        service.clock = { if (requests.size >= 5) 1_000L else 0L }

        val r = service.reverify(1_000L, day1)

        requests.size shouldBe 5
        r.aborted.shouldNotBeNull() shouldContain "deadline"
        strikeCalls.shouldBeEmpty()
    }

    @Test
    fun `max calls per run is respected`() {
        limits.maxCallsPerRun = 30
        baseline()

        service.reverify(farDeadline, day1)

        requests.size shouldBe 20 // step 1 may spend two thirds
    }

    @Test
    fun `step 0 flips periods under a live reservation in WRITE (flip-logged) and only counts in LOG`() {
        occupied +=
            object : NauSysPeriod {
                override val yachtId = 5L
                override val agencyId = 1L
                override val dateFrom = week(2)
                override val dateTo = week(3)
            }

        service.reverify(farDeadline, day1)
        marks.single() shouldBe listOf(5L, week(2), week(3), null, true, 77L, ReverifyJobs.NAUSYS_REVERIFY, "STEP0_OCCUPANCY", null, day1)

        marks.clear()
        mode = NauSysReverifyMode.LOG
        val r = service.reverify(farDeadline, day1)
        marks.shouldBeEmpty()
        counts.single() shouldBe listOf(5L, week(2), week(3), null, true)
        r.wouldFlipRows shouldBe 2
    }

    @Test
    fun `heartbeat and end lines are logged while a run is working`() {
        service.heartbeatEvery = Duration.ofMillis(5)
        callDelayMs = 3
        baseline()

        val r = service.reverify(farDeadline, day1)

        logLines().any { it.startsWith("NauSys free-offer reverify heartbeat: phase=step 1 run=77 calls=") } shouldBe true
        logLines().any { it.startsWith("NauSys free-offer reverify start: NAUSYS_REVERIFY=WRITE, run 77") } shouldBe true
        logLines().last { it.startsWith("NauSys free-offer reverify end: run 77 mode=WRITE calls=60 ") }.shouldNotBeNull()
        r.calls shouldBe 60
    }
}
