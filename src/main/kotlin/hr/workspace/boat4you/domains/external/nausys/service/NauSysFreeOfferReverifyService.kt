package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.NauSysVerifyCandidate
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.mmk.service.Heartbeat
import hr.workspace.boat4you.domains.external.mmk.service.PeriodCheckBuffer
import hr.workspace.boat4you.domains.external.mmk.service.RunTally
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * NauSys free-offer reverifier (real-price DESIGN.md WS-B2, switch `NAUSYS_REVERIFY`; the NauSys twin of the 13:30
 * MMK job). It asks NauSys again about the periods the site shows (or hides) on weak evidence and writes a status only
 * on partner evidence for exactly those dates:
 *  - Step 0 (no call, WRITE only, LOG counts): a shown row a live RESERVATION / SERVICE overlaps (zombie escape) and no
 *    booking in progress backs flips to UNAVAILABLE (STEP0_OCCUPANCY); a live partner OPTION alone leaves it.
 *  - Step 1: candidates ([OfferRepository.findNauSysVerifyCandidates], classes [NauSysVerifyClass]) grouped per
 *    (agency, date_from, date_to), one `getFreeYachts` per group exactly like the nightly grid (`ignoreOptions = true`,
 *    same `extendedDataSet`). Only HTTP 200 with `status == "OK"` is an answer; anything else, a timeout or a 429 is
 *    UNKNOWN. A yacht quoted for these dates on the routes we show is upserted through
 *    [NauSysYachtOfferSyncService.syncOffersForAsync] (never `syncOffers`, whose disappearance pass manufactures
 *    markers); its strike is cleared and its priority reset to 5.
 *  - Step 2: a flippable yacht absent from the group answer, FREE without a price or quoted on another route only is
 *    re-asked alone with its exact dates. EMPTY / UNPRICED / ROUTE_GONE = a strike; a strike from an earlier day plus
 *    today's = [OfferRepository.markNauSysPeriodUnavailable] (exact dates; the stale route rows only for ROUTE_GONE),
 *    flip-logged in the same statement. P2b (real UNDER_OPTION) and P6 (stale UNAVAILABLE) never flip: a quote is
 *    upserted with the partner's own status, an empty answer is only recorded. An empty 7-night answer inside a season
 *    whose minimal duration is longer is MINSTAY: it may hide that row on day 2 but stays out of the breakers.
 *  - Breakers (before any strike): < [MIN_DECIDED_GROUPS] decided groups, > 10 % unknown groups, or > 10 % of the
 *    baseline (grid-sample) groups empty = abort; an agency with >= 5 baseline groups more than half empty is
 *    escalated (run row + digest + ERROR line) and gets no strikes.
 *  - NauSys 429 rule (5.9.2026): single-threaded through the client's concurrency gate and retry interceptor, no own
 *    retries; 3 rate limits in a row = sleep 5 min, 10 in a run = stop. At most `maxCallsPerRun` calls, and never a
 *    call after the hard deadline the job passes in (cron tick + 2 h 20 min, gate wait included).
 *  - LOG: probes, bookkeeping and strikes; no upsert and no flip (would-upserts and would-flips counted). OFF: nothing.
 * Every decided probe is recorded in `partner_period_check` (NauSys, batched), the run in `reverify_run`
 * (NAUSYS_REVERIFY); an INFO heartbeat every [HEARTBEAT] and one `NauSys free-offer reverify end` line.
 */
@Suppress("TooManyFunctions", "LargeClass") // one job in steps; splitting it would hide the order of the breakers
@Service
class NauSysFreeOfferReverifyService(
    private val offerRepository: OfferRepository,
    private val nauSysRetryableClient: NauSysRetryableClient,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val offerSyncService: NauSysYachtOfferSyncService,
    private val externalMappingService: ExternalMappingService,
    private val externalSystemService: ExternalSystemService,
    private val yachtSyncMutex: YachtSyncMutex,
    private val periodChecks: PartnerPeriodCheckStore,
    private val runStore: ReverifyRunStore,
    private val switches: RealPriceSwitches,
    private val limits: NauSysReverifyLimits,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val MIN_DECIDED_GROUPS = 50
        const val MAX_UNKNOWN_SHARE = 0.10
        const val MAX_EMPTY_SHARE = 0.10
        const val MAX_EMPTY_SHARE_PER_AGENCY = 0.50
        const val MIN_AGENCY_GROUPS_FOR_CAP = 5
        const val RATE_LIMITS_IN_A_ROW_PAUSE = 3
        const val RATE_LIMITS_PER_RUN_STOP = 10
        val RATE_LIMIT_PAUSE: Duration = Duration.ofMinutes(5)
        val HEARTBEAT: Duration = Duration.ofMinutes(2)

        /** Step 1 may spend this share of `maxCallsPerRun`; the rest is kept for the single-yacht confirmations. */
        const val STEP1_BUDGET_SHARE = 2.0 / 3.0

        const val REASON_STEP0_OCCUPANCY = "STEP0_OCCUPANCY"
        const val REASON_TWO_STRIKE_EMPTY = "TWO_STRIKE_EMPTY"
        const val REASON_UNPRICED_TWO_DAY = "UNPRICED_TWO_DAY"
        const val REASON_ROUTE_GONE = "ROUTE_GONE"

        private const val NAUSYS_OK = "OK"
        private const val WEEK_NIGHTS = 7L
        private val NAUSYS = ExternalSystemEnum.NAUSYS
    }

    /** Test seams. */
    internal var heartbeatEvery: Duration = HEARTBEAT
    internal var clock: () -> Long = System::currentTimeMillis
    internal var sleeper: (Long) -> Unit = Thread::sleep

    /** One shown row of a period: its offer id and route (our location ids). */
    private data class ShownRow(
        val offerId: Long,
        val from: Long,
        val to: Long,
    )

    /** One candidate period (all classes it qualified for). */
    private class Period(
        val yachtId: Long,
        val externalYachtId: Long,
        val agencyId: Long,
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val classes: Set<NauSysVerifyClass>,
        val seasonMinDuration: Int?,
        val shown: List<ShownRow>,
    ) {
        val priority = classes.minOf { it.priority }
        val order = classes.minOf { it.order }
        val flippable = classes.any { it.flippable }
        val baseline = NauSysVerifyClass.P5 in classes

        /** A 7-night row inside a season sold only for longer stays: an empty answer is not a breaker signal. */
        val minStay = ChronoUnit.DAYS.between(dateFrom, dateTo) == WEEK_NIGHTS && (seasonMinDuration ?: 0) > WEEK_NIGHTS
        val label get() = "yacht=$yachtId $dateFrom..$dateTo ${classes.joinToString("/")}"
    }

    private class Group(
        val agencyId: Long,
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val periods: List<Period>,
    ) {
        val order = periods.minOf { it.order }
    }

    private enum class Verdict { QUOTED, ROUTE_GONE, UNPRICED, EMPTY }

    /** How one OK answer reads for one period: the verdict, the bookkeeping result, the rows to upsert, stale routes. */
    private class Reading(
        val verdict: Verdict,
        val result: PeriodCheckResult,
        val quotedRows: List<RestFreeYacht> = emptyList(),
        val staleOfferIds: List<Long> = emptyList(),
    )

    /** A call: null = UNKNOWN (error, non-OK body, 429, timeout), else the partner's rows. */
    private class Answer(
        val rows: List<RestFreeYacht>?,
    )

    /** Per-run state (the run is single-threaded; the heartbeat thread only reads). */
    private class Ctx(
        val today: LocalDate,
        val mode: NauSysReverifyMode,
        val tally: RunTally,
        val checks: PeriodCheckBuffer,
        val deadline: Long,
        val locations: Map<Long?, Set<Long>>,
    ) {
        val phase = AtomicReference("step 0")
        val escalated = mutableSetOf<Long>()

        @Volatile var stopReason: String? = null
        var rateLimitsInARow = 0
        var rateLimits = 0
        val writes get() = mode == NauSysReverifyMode.WRITE
    }

    class RunResult(
        val runId: Long?,
        val mode: NauSysReverifyMode,
        val calls: Int = 0,
        val quoted: Int = 0,
        val empty: Int = 0,
        val unknown: Int = 0,
        val upserts: Int = 0,
        val firstStrikes: Int = 0,
        val hiddenPeriods: Int = 0,
        val hiddenRows: Int = 0,
        val wouldFlipRows: Int = 0,
        val aborted: String? = null,
        val escalatedAgencyIds: Set<Long> = emptySet(),
    )

    @Volatile
    private var running = false

    /** The switch as the job sees it (OFF = the job only logs one line). */
    fun mode(): NauSysReverifyMode = switches.nausysReverify

    /**
     * Runs once. [deadlineMillis] = the hard stop (epoch ms) checked before every partner call and step-0 flip;
     * [today] = the run date of strikes and bookkeeping.
     */
    @Suppress("ReturnCount") // OFF and "already running" end the call before a run row exists
    fun reverify(
        deadlineMillis: Long,
        today: LocalDate = LocalDate.now(),
    ): RunResult {
        val mode = switches.nausysReverify
        if (mode == NauSysReverifyMode.OFF) {
            log.info("NauSys free-offer reverify: NAUSYS_REVERIFY=OFF - nothing to do")
            return RunResult(null, mode)
        }
        if (running) {
            log.warn("NauSys free-offer reverify already running - skipping this trigger")
            return RunResult(null, mode, aborted = "already running")
        }
        running = true
        try {
            return run(mode, deadlineMillis, today)
        } finally {
            running = false
        }
    }

    private fun run(
        mode: NauSysReverifyMode,
        deadlineMillis: Long,
        today: LocalDate,
    ): RunResult {
        val runMode = if (mode == NauSysReverifyMode.WRITE) ReverifyRunMode.WRITE else ReverifyRunMode.LOG
        val tally = RunTally(runStore, runStore.start(ReverifyJobs.NAUSYS_REVERIFY, runMode))
        val ctx = Ctx(today, mode, tally, PeriodCheckBuffer(periodChecks, today, sys = NAUSYS), deadlineMillis, locationMappings())
        log.info("NauSys free-offer reverify start: NAUSYS_REVERIFY=$mode, run ${tally.runId}, deadline ${Instant.ofEpochMilli(deadlineMillis)}")
        var aborted: String? = null
        Heartbeat(heartbeatEvery, "nausys-reverify-heartbeat") { heartbeat(ctx) }.use {
            try {
                aborted = steps(ctx)
            } finally {
                ctx.checks.flush()
                tally.finish(aborted ?: ctx.stopReason, ctx.escalated.sorted().ifEmpty { null })
            }
        }
        log.info(
            "NauSys free-offer reverify end: run ${tally.runId} mode=$mode ${tally.line()}" +
                (aborted?.let { " ABORTED: $it" } ?: "") + (ctx.stopReason?.let { " STOPPED: $it" } ?: "") +
                if (ctx.escalated.isEmpty()) "" else " escalated agencies=${ctx.escalated.sorted()}",
        )
        val c = tally.snapshot()
        return RunResult(
            tally.runId,
            mode,
            c.calls,
            c.quoted,
            c.empty,
            c.unknown,
            c.upserts,
            c.firstStrikes,
            c.hiddenPeriods,
            c.hiddenRows,
            c.wouldFlipRows,
            aborted ?: ctx.stopReason,
            ctx.escalated.toSet(),
        )
    }

    private fun heartbeat(ctx: Ctx) {
        log.info("NauSys free-offer reverify heartbeat: phase=${ctx.phase.get()} run=${ctx.tally.runId} ${ctx.tally.line()}")
        ctx.tally.flush()
    }

    /** Returns the abort reason of the breakers, or null. */
    @Suppress("ReturnCount") // each breaker ends the run on its own
    private fun steps(ctx: Ctx): String? {
        step0(ctx)
        ctx.phase.set("candidates")
        val periods = candidates(ctx.today)
        val groups = orderGroups(periods)
        log.info(
            "NauSys free-offer reverify: ${periods.size} candidate periods in ${groups.size} groups " +
                NauSysVerifyClass.entries.joinToString(prefix = "(", postfix = ")") { c -> "$c ${periods.count { c in it.classes }}" },
        )
        if (groups.isEmpty()) return null

        ctx.phase.set("step 1")
        val step1 = step1(ctx, groups)
        if (ctx.stopReason != null) {
            log.warn("NauSys free-offer reverify: stopped in step 1 (${ctx.stopReason}) - no strikes this run")
            return null
        }
        abortReason(step1)?.let {
            log.error("NauSys free-offer reverify ABORTED, no strikes written: $it")
            return it
        }
        ctx.escalated += step1.suspectAgencies()
        ctx.escalated.forEach { agency ->
            log.error(
                "NauSys free-offer reverify: agency $agency has ${step1.agencyEmpty[agency]} of ${step1.agencyDecided[agency]} baseline " +
                    "groups empty - looks like a broken feed or a whole-fleet withdrawal; NOT hidden automatically, decide by hand",
            )
        }

        ctx.phase.set("step 2")
        val toConfirm = step1.toConfirm.filter { it.agencyId !in ctx.escalated }
        log.info("NauSys free-offer reverify step 2: ${toConfirm.size} periods to confirm one by one (${step1.toConfirm.size - toConfirm.size} of escalated agencies left)")
        for (p in toConfirm) {
            if (!mayCall(ctx, limits.maxCallsPerRun)) break
            confirm(ctx, p)
        }
        return null
    }

    // ---- step 0

    private fun step0(ctx: Ctx) {
        ctx.phase.set("step 0")
        val periods = offerRepository.findNauSysOccupiedShownPeriods(NAUSYS.value.toLong(), ctx.today, limits.step0MaxPeriods)
        if (periods.isEmpty()) return
        var flippedPeriods = 0
        for (p in periods) {
            if (clock() >= ctx.deadline) {
                ctx.stopReason = "hard deadline reached in step 0"
                return
            }
            if (!ctx.writes) {
                ctx.tally.wouldFlipRows.addAndGet(offerRepository.countNauSysPeriodFlippable(p.yachtId, p.dateFrom, p.dateTo, null, true))
                continue
            }
            val ran =
                yachtSyncMutex.runExclusiveYachtWrite(p.yachtId) {
                    val flipped =
                        offerRepository.markNauSysPeriodUnavailable(
                            p.yachtId,
                            p.dateFrom,
                            p.dateTo,
                            null,
                            true,
                            ctx.tally.runId,
                            ReverifyJobs.NAUSYS_REVERIFY,
                            REASON_STEP0_OCCUPANCY,
                            null,
                            ctx.today,
                        )
                    if (hidden(ctx, p.yachtId, p.dateFrom, p.dateTo, flipped)) flippedPeriods++
                }
            if (!ran) log.info("NauSys free-offer reverify step 0: yacht=${p.yachtId} skipped, another sync is writing it")
        }
        log.info(
            "NauSys free-offer reverify step 0: ${periods.size} periods under a live reservation, " +
                if (ctx.writes) "$flippedPeriods flipped" else "${ctx.tally.wouldFlipRows.get()} rows would flip (LOG)",
        )
    }

    // ---- candidates

    private fun candidates(today: LocalDate): List<Period> =
        offerRepository
            .findNauSysVerifyCandidates(
                NAUSYS.value.toLong(),
                today,
                limits.capP1,
                limits.capP2,
                limits.capP2b,
                limits.capP3,
                limits.capP4,
                limits.capP5,
                limits.capP6,
            ).groupBy { Triple(it.yachtId, it.dateFrom, it.dateTo) }
            .map { (_, rows) -> toPeriod(rows) }

    private fun toPeriod(rows: List<NauSysVerifyCandidate>): Period {
        val r = rows.first()
        return Period(
            yachtId = r.yachtId,
            externalYachtId = r.externalYachtId,
            agencyId = r.agencyId,
            dateFrom = r.dateFrom,
            dateTo = r.dateTo,
            classes = rows.map { NauSysVerifyClass.fromCode(it.cls) }.toSet(),
            seasonMinDuration = rows.mapNotNull { it.seasonMinDuration }.maxOrNull(),
            shown = parseShown(r.shownRows),
        )
    }

    private fun parseShown(value: String?): List<ShownRow> =
        value
            ?.split(',')
            ?.mapNotNull { item ->
                val id = item.substringBefore(':').toLongOrNull()
                val route = item.substringAfter(':', "")
                val from = route.substringBefore('>').toLongOrNull()
                val to = route.substringAfter('>', "").toLongOrNull()
                if (id == null || from == null || to == null) null else ShownRow(id, from, to)
            }.orEmpty()

    /** The P5 baseline first (the breakers read it), then by class order, earliest period, agency. */
    private fun orderGroups(periods: List<Period>): List<Group> {
        val groups =
            periods
                .groupBy { Triple(it.agencyId, it.dateFrom, it.dateTo) }
                .map { (k, ps) -> Group(k.first, k.second, k.third, ps.distinctBy { it.externalYachtId }) }
        val baseline =
            groups
                .filter { g -> g.periods.any { it.baseline } }
                .sortedWith(compareBy({ it.dateFrom }, { it.agencyId }))
                .take(limits.baselineGroups)
        val baselineKeys = baseline.map { Triple(it.agencyId, it.dateFrom, it.dateTo) }.toSet()
        val rest =
            groups
                .filter { Triple(it.agencyId, it.dateFrom, it.dateTo) !in baselineKeys }
                .sortedWith(compareBy({ it.order }, { it.dateFrom }, { it.agencyId }))
        return baseline + rest
    }

    // ---- step 1

    private class Step1 {
        var attempted = 0
        var decided = 0
        var unknown = 0
        var baselineDecided = 0
        var baselineEmpty = 0
        var allDecided = 0
        var allEmpty = 0
        val agencyDecided = mutableMapOf<Long, Int>()
        val agencyEmpty = mutableMapOf<Long, Int>()
        val allAgencyDecided = mutableMapOf<Long, Int>()
        val allAgencyEmpty = mutableMapOf<Long, Int>()
        val toConfirm = mutableListOf<Period>()

        /** The baseline is the grid sample; without enough of it every decided group counts (the safe direction). */
        val useBaseline get() = baselineDecided >= MIN_DECIDED_GROUPS

        fun suspectAgencies(): Set<Long> {
            val decided = if (useBaseline) agencyDecided else allAgencyDecided
            val empty = if (useBaseline) agencyEmpty else allAgencyEmpty
            return decided
                .filter { (agency, d) -> d >= MIN_AGENCY_GROUPS_FOR_CAP && (empty[agency] ?: 0).toDouble() / d > MAX_EMPTY_SHARE_PER_AGENCY }
                .keys
        }
    }

    private fun step1(
        ctx: Ctx,
        groups: List<Group>,
    ): Step1 {
        val s = Step1()
        val budget = (limits.maxCallsPerRun * STEP1_BUDGET_SHARE).toInt().coerceAtLeast(1)
        for (g in groups) {
            if (!mayCall(ctx, budget)) break
            s.attempted++
            val rows = ask(ctx, g.periods.map { it.externalYachtId }, g.dateFrom, g.dateTo).rows
            if (rows == null) {
                s.unknown++
                g.periods.forEach { record(ctx, it, PeriodCheckResult.UNKNOWN) }
            } else {
                s.decided++
                readGroup(ctx, s, g, rows)
            }
        }
        log.info(
            "NauSys free-offer reverify step 1: ${s.attempted} groups asked, ${s.decided} decided, ${s.unknown} unknown; " +
                "baseline ${s.baselineDecided} decided / ${s.baselineEmpty} empty; all ${s.allDecided} / ${s.allEmpty} empty; " +
                "${s.toConfirm.size} periods to confirm",
        )
        return s
    }

    /** One decided group answer: clean quotes upserted, flippable doubts queued for step 2, evidence-only recorded. */
    private fun readGroup(
        ctx: Ctx,
        s: Step1,
        g: Group,
        rows: List<RestFreeYacht>,
    ) {
        val upsert = mutableListOf<RestFreeYacht>()
        val emptyFlags = mutableListOf<Pair<Period, Boolean>>()
        for (p in g.periods) {
            val reading = read(ctx, p, rows)
            if (!p.minStay) emptyFlags += p to (reading.verdict == Verdict.EMPTY || reading.verdict == Verdict.UNPRICED)
            when {
                reading.verdict == Verdict.QUOTED -> {
                    quoted(ctx, p, reading)
                    upsert += reading.quotedRows
                }
                p.flippable -> s.toConfirm += p // the multi-yacht answer is not evidence enough to strike
                else -> evidenceOnly(ctx, p, reading, upsert)
            }
        }
        upsertRows(ctx, upsert)
        breakerCounts(s, g, emptyFlags)
    }

    /** P2b / P6: a quote (any route) is upserted with the partner's own status; an empty answer is only recorded. */
    private fun evidenceOnly(
        ctx: Ctx,
        p: Period,
        reading: Reading,
        upsert: MutableList<RestFreeYacht>,
    ) {
        record(ctx, p, reading.result)
        if (reading.quotedRows.isNotEmpty()) {
            ctx.tally.quoted.incrementAndGet()
            upsert += reading.quotedRows
        } else {
            ctx.tally.empty.incrementAndGet()
        }
    }

    private fun breakerCounts(
        s: Step1,
        g: Group,
        emptyFlags: List<Pair<Period, Boolean>>,
    ) {
        if (emptyFlags.isEmpty()) return // only MINSTAY periods: outside the breakers
        val groupEmpty = emptyFlags.all { it.second }
        s.allDecided++
        s.allAgencyDecided.merge(g.agencyId, 1, Int::plus)
        if (groupEmpty) {
            s.allEmpty++
            s.allAgencyEmpty.merge(g.agencyId, 1, Int::plus)
        }
        val base = emptyFlags.filter { it.first.baseline }
        if (base.isEmpty()) return
        s.baselineDecided++
        s.agencyDecided.merge(g.agencyId, 1, Int::plus)
        if (base.all { it.second }) {
            s.baselineEmpty++
            s.agencyEmpty.merge(g.agencyId, 1, Int::plus)
        }
    }

    private fun abortReason(s: Step1): String? {
        val decided = if (s.useBaseline) s.baselineDecided else s.allDecided
        val empty = if (s.useBaseline) s.baselineEmpty else s.allEmpty
        val share = if (decided == 0) 0.0 else empty.toDouble() / decided
        return when {
            s.decided < MIN_DECIDED_GROUPS -> "only ${s.decided} of ${s.attempted} groups could be decided - partner unreachable or too few candidates"
            s.attempted > 0 && s.unknown.toDouble() / s.attempted > MAX_UNKNOWN_SHARE -> "${s.unknown} of ${s.attempted} group calls unknown - partner degraded"
            share > MAX_EMPTY_SHARE ->
                "${"%.1f".format(share * 100)} % of ${if (s.useBaseline) "baseline (grid sample)" else "decided"} groups empty " +
                    "($empty of $decided) - NauSys outage, not a fleet-wide withdrawal"
            else -> null
        }
    }

    // ---- step 2

    private fun confirm(
        ctx: Ctx,
        p: Period,
    ) {
        val rows = ask(ctx, listOf(p.externalYachtId), p.dateFrom, p.dateTo).rows
        if (rows == null) {
            record(ctx, p, PeriodCheckResult.UNKNOWN)
            return
        }
        val reading = read(ctx, p, rows)
        when (reading.verdict) {
            Verdict.QUOTED -> {
                quoted(ctx, p, reading)
                upsertRows(ctx, reading.quotedRows)
            }
            Verdict.ROUTE_GONE -> {
                // The quoted route is positive evidence and is written; the rows of the other routes get a strike.
                ctx.tally.quoted.incrementAndGet()
                upsertRows(ctx, reading.quotedRows)
                strike(ctx, p, reading.result, REASON_ROUTE_GONE, reading.staleOfferIds)
            }
            Verdict.UNPRICED -> {
                ctx.tally.empty.incrementAndGet()
                strike(ctx, p, reading.result, REASON_UNPRICED_TWO_DAY, null)
            }
            Verdict.EMPTY -> {
                ctx.tally.empty.incrementAndGet()
                strike(ctx, p, reading.result, REASON_TWO_STRIKE_EMPTY, null)
            }
        }
    }

    /**
     * Records a strike; a strike from an earlier day plus today's hides exactly these dates (WRITE) or is counted
     * (LOG). [offerIds] = only these rows of the period (stale routes), null = every row in the write scope.
     */
    private fun strike(
        ctx: Ctx,
        p: Period,
        result: PeriodCheckResult,
        reason: String,
        offerIds: List<Long>?,
    ) {
        val ran =
            yachtSyncMutex.runExclusiveYachtWrite(p.yachtId) {
                val firstEmptyOn = periodChecks.recordFirstStrike(NAUSYS, p.yachtId, p.dateFrom, p.dateTo, p.priority, result, ctx.today)
                if (!firstEmptyOn.isBefore(ctx.today)) {
                    ctx.tally.firstStrikes.incrementAndGet()
                    return@runExclusiveYachtWrite
                }
                val targets: List<Long?> = offerIds ?: listOf(null)
                if (!ctx.writes) {
                    ctx.tally.wouldFlipRows.addAndGet(targets.sumOf { offerRepository.countNauSysPeriodFlippable(p.yachtId, p.dateFrom, p.dateTo, it, false) })
                    return@runExclusiveYachtWrite
                }
                val flipped =
                    targets.sumOf { offerId ->
                        offerRepository.markNauSysPeriodUnavailable(
                            p.yachtId,
                            p.dateFrom,
                            p.dateTo,
                            offerId,
                            false,
                            ctx.tally.runId,
                            ReverifyJobs.NAUSYS_REVERIFY,
                            reason,
                            firstEmptyOn,
                            ctx.today,
                        )
                    }
                if (!hidden(ctx, p.yachtId, p.dateFrom, p.dateTo, flipped)) {
                    // Nothing left to flip (a partner hold or a booking arrived, another writer hid it): no hide happened.
                    periodChecks.clearStrike(NAUSYS, p.yachtId, p.dateFrom, p.dateTo)
                }
            }
        if (!ran) log.info("NauSys free-offer reverify: ${p.label} strike skipped, another sync is writing the yacht")
    }

    private fun hidden(
        ctx: Ctx,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        flipped: Int,
    ): Boolean {
        if (flipped <= 0) return false
        periodChecks.markHidden(NAUSYS, yachtId, dateFrom, dateTo, flipped, ctx.today)
        ctx.tally.hiddenPeriods.incrementAndGet()
        ctx.tally.hiddenRows.addAndGet(flipped)
        return true
    }

    // ---- reading an answer

    /**
     * Reads an OK answer for one period: no row for exactly these dates = EMPTY (MINSTAY inside a longer-stay season);
     * only FREE rows without a price = UNPRICED; otherwise QUOTED, or ROUTE_GONE when a route we show is not among the
     * quoted ones (only when every quoted location is mapped: a mapping gap is ours, not evidence).
     */
    @Suppress("ReturnCount") // one return per verdict, in the order the evidence is read
    private fun read(
        ctx: Ctx,
        p: Period,
        rows: List<RestFreeYacht>,
    ): Reading {
        val exact = rows.filter { it.yachtId == p.externalYachtId && it.periodFrom?.value == p.dateFrom && it.periodTo?.value == p.dateTo }
        if (exact.isEmpty()) return Reading(Verdict.EMPTY, if (p.minStay) PeriodCheckResult.MINSTAY else PeriodCheckResult.EMPTY)
        val quoted = exact.filterNot(::isFreeUnpriced)
        if (quoted.isEmpty()) return Reading(Verdict.UNPRICED, PeriodCheckResult.UNPRICED)
        val statuses = quoted.map { OfferStatus.fromNausysValue(it.status) }
        val result =
            when {
                OfferStatus.FREE in statuses -> PeriodCheckResult.QUOTED_FREE
                statuses.any { it == OfferStatus.OPTION || it == OfferStatus.OPTION_WAITING } -> PeriodCheckResult.QUOTED_OPTION
                else -> PeriodCheckResult.QUOTED_OTHER
            }
        val routes = quoted.map { ctx.locations[it.locationFromId] to ctx.locations[it.locationToId] }
        if (routes.any { it.first == null || it.second == null }) return Reading(Verdict.QUOTED, result, exact)
        val stale = p.shown.filter { s -> routes.none { (f, t) -> s.from in f!! && s.to in t!! } }
        return if (stale.isEmpty()) {
            Reading(Verdict.QUOTED, result, exact)
        } else {
            Reading(Verdict.ROUTE_GONE, PeriodCheckResult.ROUTE_GONE, exact, stale.map { it.offerId })
        }
    }

    private fun isFreeUnpriced(r: RestFreeYacht): Boolean {
        val price = r.price?.clientPrice?.toBigDecimalOrNull()
        return OfferStatus.fromNausysValue(r.status) == OfferStatus.FREE && (price == null || price.signum() <= 0)
    }

    /** A clean quote: bookkeeping, the strike closed and the priority back to 5. */
    private fun quoted(
        ctx: Ctx,
        p: Period,
        reading: Reading,
    ) {
        ctx.tally.quoted.incrementAndGet()
        record(ctx, p, reading.result)
        runCatching { periodChecks.clearStrike(NAUSYS, p.yachtId, p.dateFrom, p.dateTo, PeriodCheckPriority.SAMPLE) }
            .onFailure { log.warn("NauSys free-offer reverify: strike of ${p.label} not cleared: ${it.message}") }
    }

    /** WRITE: the partner's rows for exactly these dates through the warm-path upsert (no disappearance); LOG: counted. */
    private fun upsertRows(
        ctx: Ctx,
        rows: List<RestFreeYacht>,
    ) {
        if (rows.isEmpty()) return
        val yachts = rows.mapNotNull { it.yachtId }.distinct().size
        ctx.tally.upserts.addAndGet(yachts)
        if (!ctx.writes) return
        runCatching { offerSyncService.syncOffersForAsync(rows) }
            .onFailure { log.error("NauSys free-offer reverify: upsert of $yachts yachts failed", it) }
    }

    private fun record(
        ctx: Ctx,
        p: Period,
        result: PeriodCheckResult,
    ) {
        ctx.checks.add(PeriodCheck(p.yachtId, p.dateFrom, p.dateTo, p.priority, result))
    }

    // ---- calls

    /** Budget, deadline and the 429 stop, checked before every call. */
    private fun mayCall(
        ctx: Ctx,
        budget: Int,
    ): Boolean {
        if (ctx.stopReason == null && clock() >= ctx.deadline) ctx.stopReason = "hard deadline reached, the rest runs tomorrow"
        return ctx.stopReason == null && ctx.tally.calls.get() < budget
    }

    @Suppress("TooGenericExceptionCaught") // any partner failure is UNKNOWN, never evidence
    private fun ask(
        ctx: Ctx,
        externalYachtIds: List<Long>,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): Answer {
        ctx.tally.calls.incrementAndGet()
        return try {
            val response =
                nauSysRetryableClient.getFreeYachts(
                    RestFreeYachtsRequest(
                        credentials = nauSysAuthProvider.auth,
                        periodFrom = NauSysDateWrapper(dateFrom.format(NauSysDateWrapper.DATE_FORMATTER)),
                        periodTo = NauSysDateWrapper(dateTo.format(NauSysDateWrapper.DATE_FORMATTER)),
                        yachts = externalYachtIds,
                        extendedDataSet = NAUSYS_GRID_EXTENDED_DATA_SET,
                        ignoreOptions = true,
                    ),
                )
            ctx.rateLimitsInARow = 0
            if (response.status != NAUSYS_OK) {
                ctx.tally.unknown.incrementAndGet()
                log.warn(
                    "NauSys free-offer reverify: $dateFrom..$dateTo (${externalYachtIds.size} yachts) answered " +
                        "status=${response.status} errorCode=${response.errorCode} - unknown",
                )
                Answer(null)
            } else {
                Answer(response.freeYachts.orEmpty())
            }
        } catch (e: NauSysRateLimitedException) {
            ctx.tally.unknown.incrementAndGet()
            rateLimited(ctx, e)
            Answer(null)
        } catch (e: Exception) {
            ctx.tally.unknown.incrementAndGet()
            ctx.rateLimitsInARow = 0
            log.warn("NauSys free-offer reverify call failed for $dateFrom..$dateTo (${externalYachtIds.size} yachts): ${e.message}")
            Answer(null)
        }
    }

    /** House rule 5.9.2026: no own retries; 3 in a row = pause 5 min, 10 in a run = stop (the rest runs tomorrow). */
    private fun rateLimited(
        ctx: Ctx,
        e: NauSysRateLimitedException,
    ) {
        ctx.rateLimits++
        ctx.rateLimitsInARow++
        log.warn("NauSys free-offer reverify: rate limited (${e.message}), ${ctx.rateLimits} this run, ${ctx.rateLimitsInARow} in a row")
        when {
            ctx.rateLimits >= RATE_LIMITS_PER_RUN_STOP -> ctx.stopReason = "${ctx.rateLimits} NauSys rate limits this run - stopped, the rest runs tomorrow"
            ctx.rateLimitsInARow >= RATE_LIMITS_IN_A_ROW_PAUSE -> {
                if (clock() + RATE_LIMIT_PAUSE.toMillis() >= ctx.deadline) {
                    ctx.stopReason = "rate limited and no time left for the ${RATE_LIMIT_PAUSE.toMinutes()}-min pause before the hard deadline"
                } else {
                    log.warn("NauSys free-offer reverify: ${ctx.rateLimitsInARow} rate limits in a row - pausing ${RATE_LIMIT_PAUSE.toMinutes()} min")
                    ctx.phase.set("${ctx.phase.get()} (rate-limit pause)")
                    sleeper(RATE_LIMIT_PAUSE.toMillis())
                    ctx.phase.set(ctx.phase.get().removeSuffix(" (rate-limit pause)"))
                    ctx.rateLimitsInARow = 0
                }
            }
        }
    }

    /** NauSys location id -> our location ids (one partner location may map to more than one of ours). */
    private fun locationMappings(): Map<Long?, Set<Long>> =
        runCatching {
            val nausys = externalSystemService.findById(NAUSYS.value.toLong())
            externalMappingService
                .getCachedAllMappingsByType(Location::class.simpleName.toString(), nausys)
                .filter { it.systemId != null }
                .groupBy({ it.externalId }, { it.systemId!! })
                .mapValues { it.value.toSet() }
        }.onFailure { log.warn("NauSys free-offer reverify: location mappings not loaded, routes are not compared this run: ${it.message}") }
            .getOrDefault(emptyMap())
}
