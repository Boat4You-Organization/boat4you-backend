package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkOptionVerifyMode
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.StaleMmkOfferCombo
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheck
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import org.openapitools.client.mmk.model.Flexibility
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.openapitools.client.mmk.model.Offer as MmkOffer

/**
 * Mirror image of [MmkStaleOfferReverifyService]: hides offers the site shows as bookable that MMK no longer sells —
 * FREE rows of any length and OPTION rows no live option backs (26.9.2026: EMA 12210 showed a phantom 28-night block
 * 12.6.-10.7.2027 as the match for a one-week search; its weeks were already hidden, the long OPTION rows were not).
 *
 * Why: since the agency-level sweep became upsert-only (20.7.2026) nothing removes a FREE week the partner has
 * withdrawn. An agency that has not published next season's price list keeps showing a full year of "free" weeks
 * at last year's prices (LA MAR, 21.9.2026: 10,375 rows on 189 yachts hidden by hand). Occupancy is mirrored by the
 * availability sync, but a withdrawn price list is not occupancy.
 *
 * Evidence rules, all learned the hard way (55de710 hid ~81k sellable weeks and was reverted in 58d4623):
 *  - The ONLY accepted evidence is the one call shape MMK answers reliably: exact dates, `flexibility=1`, a single
 *    `yachtId`. The agency feed and the per-yacht year call are NOT evidence. The same call quotes periods of any
 *    length (26.9.2026: 114 of 120 random FREE 3-28-night rows quoted; the 6 empty ones started within 2 days or
 *    were a Sunday check-in MMK does not sell).
 *  - Evidence scope == write scope: we ask about a period with its own dates and flip only rows with exactly those
 *    dates, never a row that merely overlaps.
 *  - A call failure is "unknown", never "withdrawn".
 *  - A period is hidden only when two DAILY runs on different days both found it empty (`mmk_free_week_strike`,
 *    V9_59). Two calls 150 ms apart see the same 200-[] maintenance window; two days do not.
 *  - Periods are grouped per yacht, year and shape (7-night weeks / every other length), and each group is sampled
 *    from its own periods: a long block MMK stopped quoting is found even while the yacht's weeks still sell.
 *  - Breakers before any write: too few decided week groups, too many call failures, too large a share of the
 *    fleet's week groups empty (then nothing is written), too large a share of the other groups empty (then only
 *    those are skipped), or too large a share of ONE agency's groups of a shape empty (an agency whose feed broke
 *    looks exactly like one that withdrew its list; that decision goes to a human via the ERROR log).
 *  - A wall-clock budget, because `getOffers` retries 3x with a 60 s read timeout and an outage would otherwise
 *    run into the 16:40 availability slot.
 *
 * A hidden period stays a candidate of the nightly UNAVAILABLE->FREE reverifier (with a 7-day back-off), which turns
 * it back to FREE with the new price once the agency publishes it; the strike row is deleted the moment MMK quotes
 * the period again. A hidden period that comes back without a quote (the agency sweep, an on-demand search) starts a
 * new two-day cycle.
 *
 * Real-price rule (Mario 26.9.2026, decisions 14.10.2026; DESIGN.md WS-B "Core rule" and B1), behind the DB switch
 * MMK_OPTION_VERIFY (default OFF = the job above, unchanged, plus bookkeeping):
 *  - Every decided probe is recorded in `partner_period_check` (MMK) and every run in `reverify_run`: `MMK_FREE` for
 *    the steps above (always), `MMK_OPTION` for the switch-governed work below. Every hide writes its flip-log row in
 *    the same statement ([OfferRepository.markMmkPeriodUnavailable]), so a run can be undone (PartnerFlipUndoService).
 *  - Step 2c: stale OPTION / OPTION_WAITING periods no live hold backs, rotated on `partner_period_check`
 *    ([OfferRepository.findStaleMmkOptionPeriods], at most [MMK_OPTION_VERIFY_MAX_PER_RUN] a run), each asked with
 *    its own exact dates. Step 2b: open strikes from earlier days (e.g. the on-demand path) not decided today.
 *  - A quote is upserted only when it matters: always for a 2c/2b period, otherwise only on drift (status differs or
 *    the partner total moved by more than 1 %). The upsert is [MmkYachtOfferSyncService.syncOffers] called UNWRAPPED:
 *    it takes the per-yacht write lock itself, and nesting it inside runExclusiveYachtWrite would make its try-lock
 *    fail and silently drop the quote. Probing never holds a lock.
 *  - Two empty answers on different days hide exactly those dates; OPTION_WAITING enters the write scope only with
 *    the switch ON. LOG: probes, bookkeeping and strikes, but no upsert and no flip (would-counts in `reverify_run`).
 *  - INFO heartbeat every [HEARTBEAT] for the cusma3 restart gate.
 */
@Service
class MmkFreeOfferReverifyService(
    private val offerRepository: OfferRepository,
    private val mmkRetryableClient: MmkRetryableClient,
    private val yachtSyncMutex: YachtSyncMutex,
    private val jdbcTemplate: JdbcTemplate,
    private val mmkYachtOfferSyncService: MmkYachtOfferSyncService,
    private val periodChecks: PartnerPeriodCheckStore,
    private val runStore: ReverifyRunStore,
    private val switches: RealPriceSwitches,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        private const val WORKER_COUNT = 8
        private const val CALL_PACING_MS = 150L
        private const val MAX_CONSECUTIVE_ERRORS_PER_YACHT = 3
        private const val PROGRESS_LOG_EVERY = 250

        // 21.9.2026 baseline: 199 of 6,929 yacht-seasons (2.9 %) fully unquoted; 22.9.2026 first run: 222 of 13,954
        // (1.6 %). Three times that is not a normal day. Applied to each shape (weeks / other lengths) on its own.
        const val MAX_EMPTY_SHARE = 0.10

        // One agency at a time is the realistic failure (credentials, a company flag, a 200-[] answer for its
        // yachts). Above this share of an agency's seasons we stop trusting the answer and ask a human.
        const val MAX_EMPTY_SHARE_PER_AGENCY = 0.50
        const val MIN_AGENCY_SEASONS_FOR_CAP = 5

        // Below this many decided seasons there is no fleet to compare against: the partner is sick, write nothing.
        const val MIN_DECIDED_SEASONS = 50
        const val MAX_UNKNOWN_SHARE = 0.10

        // Step 2 is bounded per run; the remainder is picked up tomorrow (that is also when the 2nd strike lands).
        const val MAX_SEASONS_VERIFIED_PER_RUN = 400
        val RUN_BUDGET: Duration = Duration.ofMinutes(100)

        /** Step 2c cap (DESIGN.md B1): ~8.4k stale option periods drain in ~3 runs, then each is re-checked weekly. */
        const val MMK_OPTION_VERIFY_MAX_PER_RUN = 3000

        /** Step 2b cap: open strikes are few (on-demand strikes, strikes whose season was quoted the next day). */
        const val MAX_OPEN_STRIKES_PER_RUN = 2000

        val HEARTBEAT: Duration = Duration.ofMinutes(2)

        /** `partner_offer_flip_log.reason` of a hide on the second empty answer. */
        const val REASON_TWO_STRIKE_EMPTY = "TWO_STRIKE_EMPTY"
    }

    /** Test seam for the heartbeat period. */
    internal var heartbeatEvery: Duration = HEARTBEAT

    /** One probed period. Copied off the JPA projection at once: ~470k proxies would hold ~250 MB for the whole run. */
    private class Period(
        val externalYachtId: Long,
        val agencyId: Long,
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
    ) {
        val weekly get() = ChronoUnit.DAYS.between(dateFrom, dateTo) == 7L
    }

    /** The bookable-looking periods of one yacht, year and shape ([weekly] = 7 nights). */
    private class Season(
        val yachtId: Long,
        val externalYachtId: Long,
        val agencyId: Long,
        val year: Int,
        val weekly: Boolean,
        val periods: List<Period>,
    ) {
        val label get() = "$year ${if (weekly) "weeks" else "other lengths"}"
    }

    private enum class Probe { QUOTED, EMPTY, UNKNOWN }

    /** A probe and, for QUOTED, the partner's rows (the upsert input). */
    private class Answer(
        val probe: Probe,
        val response: List<MmkOffer> = emptyList(),
    )

    /**
     * How one verification step treats its answers. [d2Candidates]: a quote is always upserted (2c/2b), otherwise
     * only on drift. [writeFlips] false = LOG (would-flip rows counted, nothing flipped).
     */
    private class Step(
        val name: String,
        val priority: Int,
        val tally: RunTally,
        val job: String,
        val d2Candidates: Boolean,
        val writeFlips: Boolean,
        val includeOptionWaiting: Boolean,
    )

    /** Per-run state shared by the worker threads. */
    private class Ctx(
        val today: LocalDate,
        val mode: MmkOptionVerifyMode,
        val free: RunTally,
        val option: RunTally?,
        val checks: PeriodCheckBuffer,
        val deadline: Long,
    ) {
        val outOfTime = AtomicBoolean(false)
        val phase = AtomicReference("probe")
    }

    /** What the free-offer steps (1 and 2) decided; the option steps reuse their breakers. */
    private class FreeOutcome(
        val seasons: Int,
        val emptySeasons: Int,
        val aborted: String?,
        val suspectAgencies: Set<Pair<Long, Boolean>> = emptySet(),
    )

    class RunResult(
        val seasons: Int,
        val emptySeasons: Int,
        val firstStrikes: Int,
        val hiddenPeriods: Int,
        val hiddenRows: Int,
        val aborted: String?,
        /** MMK_OPTION run (switch LOG/ON), else 0 / null: upserts (would-upserts in LOG), strikes, hides, would-flips. */
        val upserts: Int = 0,
        val optionFirstStrikes: Int = 0,
        val optionHiddenPeriods: Int = 0,
        val wouldFlipRows: Int = 0,
        val freeRunId: Long? = null,
        val optionRunId: Long? = null,
    )

    @Volatile
    private var running = false

    fun reverifyFreeOffers(today: LocalDate = LocalDate.now()): RunResult {
        if (running) {
            log.warn("MMK free-offer reverify already running - skipping this trigger")
            return RunResult(0, 0, 0, 0, 0, "already running")
        }
        running = true
        try {
            return run(today)
        } finally {
            running = false
        }
    }

    private fun run(today: LocalDate): RunResult {
        val mode = switches.mmkOptionVerify
        val free = RunTally(runStore, runStore.start(ReverifyJobs.MMK_FREE, ReverifyRunMode.WRITE))
        val option = startOptionRun(mode)
        val ctx = Ctx(today, mode, free, option, PeriodCheckBuffer(periodChecks, today), System.currentTimeMillis() + RUN_BUDGET.toMillis())
        log.info("MMK free-offer reverify: MMK_OPTION_VERIFY=$mode, run ${free.runId}${option?.let { " + option run ${it.runId}" } ?: ""}")
        var outcome = FreeOutcome(0, 0, null)
        Heartbeat(heartbeatEvery, "mmk-free-heartbeat") { heartbeat(ctx) }.use {
            try {
                outcome = freeSteps(ctx)
                option?.let { optionStepsIfHealthy(ctx, it, outcome) }
            } finally {
                finishRuns(ctx, outcome)
            }
        }
        log.info("MMK free-offer reverify end: free run ${free.runId} ${free.line()}${option?.let { "; option run ${it.runId} ${it.line()}" } ?: ""}")
        return result(outcome, free, option)
    }

    /** The MMK_OPTION run exists only when the switch is LOG or ON (OFF = today's job, MMK_FREE run only). */
    private fun startOptionRun(mode: MmkOptionVerifyMode): RunTally? =
        when (mode) {
            MmkOptionVerifyMode.OFF -> null
            MmkOptionVerifyMode.LOG -> RunTally(runStore, runStore.start(ReverifyJobs.MMK_OPTION, ReverifyRunMode.LOG))
            MmkOptionVerifyMode.ON -> RunTally(runStore, runStore.start(ReverifyJobs.MMK_OPTION, ReverifyRunMode.WRITE))
        }

    /** Steps 2c/2b only on a healthy partner: the probe breakers vouch for it, and without probes nothing does. */
    private fun optionStepsIfHealthy(
        ctx: Ctx,
        option: RunTally,
        outcome: FreeOutcome,
    ) {
        when {
            outcome.aborted != null -> log.warn("MMK free-offer reverify: steps 2c/2b skipped, the probe breakers fired")
            outcome.seasons == 0 -> log.warn("MMK free-offer reverify: steps 2c/2b skipped, no probe evidence of partner health")
            else -> optionSteps(ctx, option, outcome.suspectAgencies)
        }
    }

    private fun finishRuns(
        ctx: Ctx,
        outcome: FreeOutcome,
    ) {
        ctx.checks.flush()
        val stopped = if (ctx.outOfTime.get()) "stopped at the ${RUN_BUDGET.toMinutes()}-min time budget, the rest runs tomorrow" else null
        ctx.free.finish(outcome.aborted ?: stopped, outcome.suspectAgencies.map { it.first }.distinct().ifEmpty { null })
        ctx.option?.finish(outcome.aborted?.let { "probe breakers fired: $it" } ?: stopped, null)
    }

    private fun result(
        outcome: FreeOutcome,
        free: RunTally,
        option: RunTally?,
    ): RunResult {
        val f = free.snapshot()
        val o = option?.snapshot()
        return RunResult(
            seasons = outcome.seasons,
            emptySeasons = outcome.emptySeasons,
            firstStrikes = f.firstStrikes,
            hiddenPeriods = f.hiddenPeriods,
            hiddenRows = f.hiddenRows,
            aborted = outcome.aborted,
            upserts = o?.upserts ?: 0,
            optionFirstStrikes = o?.firstStrikes ?: 0,
            optionHiddenPeriods = o?.hiddenPeriods ?: 0,
            wouldFlipRows = o?.wouldFlipRows ?: 0,
            freeRunId = free.runId,
            optionRunId = option?.runId,
        )
    }

    private fun heartbeat(ctx: Ctx) {
        log.info(
            "MMK free-offer reverify heartbeat: phase=${ctx.phase.get()} free[${ctx.free.line()}]" +
                (ctx.option?.let { " option[${it.line()}]" } ?: ""),
        )
        ctx.free.flush()
        ctx.option?.flush()
    }

    // Steps 1 and 2 read as one sequence of breakers (the pre-real-price run(), unchanged); splitting it would hide the order.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun freeSteps(ctx: Ctx): FreeOutcome {
        val seasons =
            offerRepository
                .findShownFreeMmkCombos(ExternalSystemEnum.MMK.value.toLong())
                .asSequence()
                .map { c -> c.yachtId to Period(c.externalYachtId, c.agencyId, c.dateFrom, c.dateTo) }
                .groupBy({ (yachtId, p) -> Triple(yachtId, p.dateFrom.year, p.weekly) }, { it.second })
                .map { (key, periods) ->
                    Season(key.first, periods.first().externalYachtId, periods.first().agencyId, key.second, key.third, periods.sortedBy { it.dateFrom })
                }
        log.info(
            "MMK free-offer reverify: ${seasons.size} yacht-season groups (${seasons.count { it.weekly }} of weeks), " +
                "${seasons.sumOf { it.periods.size }} bookable-looking periods",
        )
        if (seasons.isEmpty()) return FreeOutcome(0, 0, null)

        // Step 1 - probe every group (its first, middle, last period), no writes: the breakers need the whole picture.
        val probed = AtomicInteger()
        val empty = ConcurrentLinkedQueue<Season>()
        // per shape: weekly=true / other lengths=false
        val decidedByShape = mapOf(true to AtomicInteger(), false to AtomicInteger())
        val emptyByShape = mapOf(true to AtomicInteger(), false to AtomicInteger())
        val decidedByAgency = ConcurrentHashMap<Pair<Long, Boolean>, AtomicInteger>()
        val emptyByAgency = ConcurrentHashMap<Pair<Long, Boolean>, AtomicInteger>()
        val unknown = AtomicInteger()
        runWorkers("mmk-free-probe", ConcurrentLinkedQueue(seasons), ctx, { "yacht=${it.yachtId} ${it.label}" }) { season ->
            val agencyKey = season.agencyId to season.weekly
            when (probeSeason(season, ctx)) {
                Probe.EMPTY -> {
                    empty.add(season)
                    decidedByShape.getValue(season.weekly).incrementAndGet()
                    emptyByShape.getValue(season.weekly).incrementAndGet()
                    decidedByAgency.getOrPut(agencyKey) { AtomicInteger() }.incrementAndGet()
                    emptyByAgency.getOrPut(agencyKey) { AtomicInteger() }.incrementAndGet()
                }
                Probe.QUOTED -> {
                    decidedByShape.getValue(season.weekly).incrementAndGet()
                    decidedByAgency.getOrPut(agencyKey) { AtomicInteger() }.incrementAndGet()
                }
                Probe.UNKNOWN -> unknown.incrementAndGet()
            }
            val done = probed.incrementAndGet()
            if (done % PROGRESS_LOG_EVERY == 0) log.info("MMK free-offer reverify probe progress: $done/${seasons.size}")
        }

        fun share(weekly: Boolean): Double {
            val d = decidedByShape.getValue(weekly).get()
            return if (d == 0) 0.0 else emptyByShape.getValue(weekly).get().toDouble() / d
        }
        val weeklyShare = share(true)
        val otherShare = share(false)
        log.info(
            "MMK free-offer reverify probe: weeks ${decidedByShape.getValue(true)} decided, ${emptyByShape.getValue(true)} fully unquoted " +
                "(${"%.1f".format(weeklyShare * 100)} %); other lengths ${decidedByShape.getValue(false)} decided, " +
                "${emptyByShape.getValue(false)} fully unquoted (${"%.1f".format(otherShare * 100)} %); " +
                "${unknown.get()} unknown (call failures)${if (ctx.outOfTime.get()) ", STOPPED at the time budget" else ""}",
        )
        // The week groups are the calibrated baseline: if they look wrong, the partner is sick and nothing is written.
        abortReason(seasons.size, decidedByShape.getValue(true).get(), unknown.get(), weeklyShare)?.let {
            log.error("MMK free-offer reverify ABORTED, nothing written: $it")
            return FreeOutcome(seasons.size, empty.size, it)
        }

        // Agencies whose whole fleet answers empty are not decided by this job - per shape, and a broken week feed
        // makes the agency's other lengths suspect as well.
        val suspect =
            emptyByAgency
                .filter { (key, e) ->
                    val d = decidedByAgency[key]?.get() ?: 0
                    d >= MIN_AGENCY_SEASONS_FOR_CAP && e.get().toDouble() / d > MAX_EMPTY_SHARE_PER_AGENCY
                }.keys
        suspect.forEach { (agency, weekly) ->
            log.error(
                "MMK free-offer reverify: agency $agency has ${emptyByAgency[agency to weekly]} of ${decidedByAgency[agency to weekly]} " +
                    "${if (weekly) "week" else "non-weekly"} groups unquoted - looks like a broken feed or a whole-fleet withdrawal; " +
                    "NOT hidden automatically, decide by hand",
            )
        }
        if (empty.isEmpty()) return FreeOutcome(seasons.size, 0, null, suspect)

        // Other lengths are judged on their own share once there are enough of them to have one; the weeks vouch that
        // the partner is healthy, so a high share here means MMK stopped quoting such periods, not that they are gone.
        val skipOtherLengths = decidedByShape.getValue(false).get() >= MIN_DECIDED_SEASONS && otherShare > MAX_EMPTY_SHARE
        if (skipOtherLengths) {
            log.error(
                "MMK free-offer reverify: ${"%.1f".format(otherShare * 100)} % of the non-weekly groups unquoted - " +
                    "not a plausible withdrawal; non-weekly periods NOT hidden this run, decide by hand",
            )
        }

        fun isSuspect(s: Season) = (s.agencyId to s.weekly) in suspect || (s.agencyId to true) in suspect
        val toVerify =
            empty
                .filter { !isSuspect(it) && (it.weekly || !skipOtherLengths) }
                .sortedWith(compareBy({ it.yachtId }, { it.year }, { !it.weekly }))
                .take(MAX_SEASONS_VERIFIED_PER_RUN)
        if (toVerify.size < empty.size) {
            log.info("MMK free-offer reverify: verifying ${toVerify.size} of ${empty.size} unquoted groups this run (breakers / per-run cap)")
        }

        // Step 2 - every period of the unquoted groups; a period is hidden only on its second strike on a later day.
        ctx.phase.set("verify")
        val step2 =
            Step(
                name = "step 2",
                priority = PeriodCheckPriority.SAMPLE,
                tally = ctx.free,
                job = ReverifyJobs.MMK_FREE,
                d2Candidates = false,
                writeFlips = true,
                includeOptionWaiting = ctx.mode == MmkOptionVerifyMode.ON,
            )
        val seasonsDone = AtomicInteger()
        runWorkers("mmk-free-verify", ConcurrentLinkedQueue(toVerify), ctx, { "yacht=${it.yachtId} ${it.label}" }) { season ->
            verifyPeriods(season.yachtId, season.externalYachtId, season.label, season.periods, step2, ctx) { false }
            val done = seasonsDone.incrementAndGet()
            if (done % 25 == 0) log.info("MMK free-offer reverify verify progress: $done/${toVerify.size} groups")
        }
        log.info(
            "MMK free-offer reverify done: ${ctx.free.firstStrikes.get()} periods got their first strike (hidden tomorrow if still empty), " +
                "${ctx.free.hiddenPeriods.get()} periods hidden on their second strike (${ctx.free.hiddenRows.get()} offer rows)" +
                if (ctx.outOfTime.get()) "; STOPPED at the time budget, the rest runs tomorrow" else "",
        )
        return FreeOutcome(seasons.size, empty.size, null, suspect)
    }

    /**
     * Steps 2c and 2b (switch LOG/ON): each period asked with its own exact dates, quotes always upserted (ON), empty
     * answers struck, a later-day second strike hides exactly those dates (ON) or is counted (LOG). An agency the probe
     * breakers marked suspect gets bookkeeping but no strike.
     */
    private fun optionSteps(
        ctx: Ctx,
        option: RunTally,
        suspect: Set<Pair<Long, Boolean>>,
    ) {
        val writes = ctx.mode == MmkOptionVerifyMode.ON

        fun isSuspect(
            agencyId: Long,
            p: Period,
        ) = (agencyId to p.weekly) in suspect || (agencyId to true) in suspect
        val mmk = ExternalSystemEnum.MMK.value.toLong()
        listOf(
            Step("step 2c", PeriodCheckPriority.STALE_OPTION, option, ReverifyJobs.MMK_OPTION, true, writes, true) to {
                // the rotation must see today's step 1/2 bookkeeping
                ctx.checks.flush()
                offerRepository.findStaleMmkOptionPeriods(mmk, ctx.today, ctx.today.minusDays(1), MMK_OPTION_VERIFY_MAX_PER_RUN)
            },
            Step("step 2b", PeriodCheckPriority.ON_DEMAND_OR_BOOKING, option, ReverifyJobs.MMK_OPTION, true, writes, true) to {
                ctx.checks.flush()
                offerRepository.findOpenMmkStrikePeriods(mmk, ctx.today, MAX_OPEN_STRIKES_PER_RUN)
            },
        ).forEach { (step, candidates) ->
            if (ctx.outOfTime.get()) return
            ctx.phase.set(step.name)
            val groups = candidates().groupBy { it.yachtId }.values.map(::OptionGroup)
            log.info("MMK free-offer reverify ${step.name}: ${groups.sumOf { it.periods.size }} periods on ${groups.size} yachts")
            runWorkers("mmk-option-${step.name.takeLast(2)}", ConcurrentLinkedQueue(groups), ctx, { "yacht=${it.yachtId} ${step.name}" }) { g ->
                verifyPeriods(g.yachtId, g.externalYachtId, step.name, g.periods, step, ctx) { p -> isSuspect(g.agencyId, p) }
            }
        }
    }

    /** The candidate periods of one yacht in step 2c / 2b. */
    private class OptionGroup(
        rows: List<StaleMmkOfferCombo>,
    ) {
        val yachtId = rows.first().yachtId
        val externalYachtId = rows.first().externalYachtId
        val agencyId = rows.first().agencyId
        val periods = rows.map { Period(it.externalYachtId, it.agencyId, it.dateFrom, it.dateTo) }.distinctBy { it.dateFrom to it.dateTo }
    }

    internal fun abortReason(
        total: Int,
        decided: Int,
        unknown: Int,
        emptyShare: Double,
    ): String? =
        when {
            decided < MIN_DECIDED_SEASONS -> "only $decided week groups of $total yacht-season groups could be decided - partner unreachable"
            unknown.toDouble() / total > MAX_UNKNOWN_SHARE -> "$unknown of $total probes failed - partner degraded"
            emptyShare > MAX_EMPTY_SHARE -> "${"%.1f".format(emptyShare * 100)} % of decided week groups unquoted - MMK outage, not a fleet-wide withdrawal"
            else -> null
        }

    @Suppress("ReturnCount") // first quote / first failure decides the group
    private fun probeSeason(
        season: Season,
        ctx: Ctx,
    ): Probe {
        // A group is sampled from its own periods only: a quoted week says nothing about a long block MMK stopped
        // quoting (EMA 12210 2027), so weeks and other lengths never vouch for each other. A group quoted on one
        // sample but empty elsewhere is left alone (under-hide, never over-hide).
        val p = season.periods
        val samples = listOf(p.first(), p[p.size / 2], p.last()).distinctBy { it.dateFrom to it.dateTo }
        for (period in samples) {
            val answer = ask(season.externalYachtId, period.dateFrom, period.dateTo, ctx.free)
            record(ctx, season.yachtId, period, answer, PeriodCheckPriority.SAMPLE)
            when (answer.probe) {
                Probe.QUOTED -> {
                    upsertIfItMatters(season.yachtId, period, answer.response, d2Candidate = false, ctx = ctx)
                    return Probe.QUOTED
                }
                Probe.UNKNOWN -> return Probe.UNKNOWN
                Probe.EMPTY -> {}
            }
        }
        return Probe.EMPTY
    }

    /** Asks every period of one yacht (no lock held), then writes quotes (unwrapped) and strikes (under the lock). */
    @Suppress("LongParameterList") // the yacht, its periods, the step and the run state
    private fun verifyPeriods(
        yachtId: Long,
        externalYachtId: Long,
        label: String,
        periods: List<Period>,
        step: Step,
        ctx: Ctx,
        noStrike: (Period) -> Boolean,
    ) {
        var consecutiveErrors = 0
        val emptyToday = mutableListOf<Period>()
        val quotedToday = mutableListOf<Pair<Period, List<MmkOffer>>>()
        for (period in periods) {
            val answer = ask(externalYachtId, period.dateFrom, period.dateTo, step.tally)
            record(ctx, yachtId, period, answer, step.priority)
            when (answer.probe) {
                Probe.EMPTY -> {
                    consecutiveErrors = 0
                    emptyToday += period
                }
                Probe.QUOTED -> {
                    consecutiveErrors = 0
                    quotedToday += period to answer.response
                }
                Probe.UNKNOWN -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS_PER_YACHT) {
                        log.warn("MMK free-offer reverify: abandoning yacht=$yachtId $label after $consecutiveErrors consecutive call failures")
                        break
                    }
                }
            }
        }
        // A quote is positive evidence with a price; it clears any earlier strike for that period. The upsert runs
        // here, outside any lock: syncOffers takes the per-yacht write lock itself.
        quotedToday.forEach { (period, response) ->
            clearStrike(yachtId, period.dateFrom, period.dateTo)
            upsertIfItMatters(yachtId, period, response, step.d2Candidates, ctx)
        }
        val strikeable = emptyToday.filterNot(noStrike)
        if (strikeable.isEmpty()) return

        val ran = yachtSyncMutex.runExclusiveYachtWrite(yachtId) { strikePeriods(yachtId, strikeable, step, ctx.today) }
        if (!ran) {
            log.info("MMK free-offer reverify: yacht=$yachtId skipped, another sync is writing it")
            return
        }
        log.info(
            "MMK free-offer reverify: yacht=$yachtId $label: ${strikeable.size} periods not sold by MMK " +
                "(${strikeable.first().dateFrom}..${strikeable.last().dateTo}), ${quotedToday.size} quoted",
        )
    }

    /** Runs under the per-yacht write lock: first strikes, and the hide (or LOG count) on a later-day second strike. */
    private fun strikePeriods(
        yachtId: Long,
        periods: List<Period>,
        step: Step,
        today: LocalDate,
    ) {
        for (period in periods) {
            val firstEmptyOn = firstStrike(yachtId, period.dateFrom, period.dateTo)
            if (firstEmptyOn == null) {
                recordFirstStrike(yachtId, period.dateFrom, period.dateTo, today)
                step.tally.firstStrikes.incrementAndGet()
            } else if (firstEmptyOn.isBefore(today)) {
                if (!step.writeFlips) {
                    step.tally.wouldFlipRows.addAndGet(
                        offerRepository.countMmkPeriodFlippable(yachtId, period.dateFrom, period.dateTo, step.includeOptionWaiting),
                    )
                    continue
                }
                val flipped =
                    offerRepository.markMmkPeriodUnavailable(
                        yachtId,
                        period.dateFrom,
                        period.dateTo,
                        step.tally.runId,
                        step.job,
                        REASON_TWO_STRIKE_EMPTY,
                        firstEmptyOn,
                        today,
                        step.includeOptionWaiting,
                    )
                if (flipped > 0) {
                    recordHidden(yachtId, period.dateFrom, period.dateTo, today, flipped)
                    step.tally.hiddenPeriods.incrementAndGet()
                    step.tally.hiddenRows.addAndGet(flipped)
                } else {
                    // Nothing left to flip: a partner option or our own booking arrived since the candidate
                    // query, or another writer already hid it. No hide happened, so no hide is recorded.
                    clearStrike(yachtId, period.dateFrom, period.dateTo)
                }
            }
            // firstEmptyOn == today: the same run already struck it (a restart), nothing to add.
        }
    }

    /**
     * QUOTED -> upsert only when it matters (DESIGN.md B1): a 2c/2b candidate, or drift. Switch OFF: never (today's
     * behaviour, not even the drift read); LOG: counted as a would-upsert in the MMK_OPTION run; ON: written through
     * [MmkYachtOfferSyncService.syncOffers] UNWRAPPED (it takes the per-yacht write lock itself).
     */
    private fun upsertIfItMatters(
        yachtId: Long,
        period: Period,
        response: List<MmkOffer>,
        d2Candidate: Boolean,
        ctx: Ctx,
    ) {
        val option = ctx.option ?: return
        val matters =
            d2Candidate ||
                MmkQuotes.drifted(response, period.dateFrom, period.dateTo, offerRepository.findPeriodStatusPrices(yachtId, period.dateFrom, period.dateTo))
        if (!matters) return
        if (ctx.mode == MmkOptionVerifyMode.ON) mmkYachtOfferSyncService.syncOffers(response)
        option.upserts.incrementAndGet()
    }

    private fun record(
        ctx: Ctx,
        yachtId: Long,
        period: Period,
        answer: Answer,
        priority: Int,
    ) {
        val result =
            when (answer.probe) {
                Probe.QUOTED -> MmkQuotes.result(answer.response, period.dateFrom, period.dateTo)
                Probe.EMPTY -> PeriodCheckResult.EMPTY
                Probe.UNKNOWN -> PeriodCheckResult.UNKNOWN
            }
        ctx.checks.add(PeriodCheck(yachtId, period.dateFrom, period.dateTo, priority, result))
    }

    private fun firstStrike(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): LocalDate? =
        jdbcTemplate
            .query(
                "SELECT first_empty_on FROM mmk_free_week_strike WHERE yacht_id = ? AND date_from = ? AND date_to = ? AND hidden_on IS NULL",
                { rs, _ -> rs.getObject(1, LocalDate::class.java) },
                yachtId,
                dateFrom,
                dateTo,
            ).firstOrNull()

    /**
     * Records an empty exact-date answer as a first strike. Idempotent: an open strike is never moved (so a second
     * empty answer on the same day changes nothing and one on a later day is the second strike). Also used by the
     * on-demand path (MmkYachtOfferIntegrationService, MMK_ONDEMAND_STRIKE), which never writes a status itself.
     */
    internal fun recordFirstStrike(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        today: LocalDate,
    ) {
        // A row with hidden_on set belongs to an earlier hide; the period is shown again without MMK having quoted it
        // (the agency sweep, an on-demand search), so a new two-day cycle starts. An open strike is never moved.
        jdbcTemplate.update(
            "INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (yacht_id, date_from, date_to) DO UPDATE SET first_empty_on = EXCLUDED.first_empty_on, " +
                "hidden_on = NULL, hidden_rows = 0 WHERE mmk_free_week_strike.hidden_on IS NOT NULL",
            yachtId,
            dateFrom,
            dateTo,
            today,
        )
    }

    private fun recordHidden(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        today: LocalDate,
        rows: Int,
    ) {
        jdbcTemplate.update(
            "UPDATE mmk_free_week_strike SET hidden_on = ?, hidden_rows = ? WHERE yacht_id = ? AND date_from = ? AND date_to = ?",
            today,
            rows,
            yachtId,
            dateFrom,
            dateTo,
        )
    }

    /** Called when MMK quotes the period again — by this job's probe and by the UNAVAILABLE->FREE reverifier. */
    fun clearStrike(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ) {
        jdbcTemplate.update("DELETE FROM mmk_free_week_strike WHERE yacht_id = ? AND date_from = ? AND date_to = ?", yachtId, dateFrom, dateTo)
    }

    @Suppress("TooGenericExceptionCaught") // any partner failure is UNKNOWN, never evidence
    private fun ask(
        externalYachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        tally: RunTally,
    ): Answer {
        tally.calls.incrementAndGet()
        val result =
            try {
                val response =
                    mmkRetryableClient.getOffers(
                        dateFrom = MmkDateTimeWrapper(LocalDateTime.of(dateFrom, LocalTime.MIN).format(MmkDateTimeWrapper.READ_FORMATTER)),
                        dateTo = MmkDateTimeWrapper(LocalDateTime.of(dateTo, LocalTime.MIN).format(MmkDateTimeWrapper.READ_FORMATTER)),
                        flexibility = Flexibility._1,
                        yachtId = listOf(externalYachtId),
                        showOptions = true,
                    )
                if (response.isEmpty()) {
                    tally.empty.incrementAndGet()
                    Answer(Probe.EMPTY)
                } else {
                    tally.quoted.incrementAndGet()
                    Answer(Probe.QUOTED, response)
                }
            } catch (e: Exception) {
                log.warn("MMK free-offer reverify call failed for externalYachtId=$externalYachtId $dateFrom..$dateTo: ${e.message}")
                tally.unknown.incrementAndGet()
                Answer(Probe.UNKNOWN)
            }
        Thread.sleep(CALL_PACING_MS)
        return result
    }

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements") // one bad item never stops a worker
    private fun <T> runWorkers(
        name: String,
        queue: ConcurrentLinkedQueue<T>,
        ctx: Ctx,
        describe: (T) -> String,
        work: (T) -> Unit,
    ) {
        val latch = CountDownLatch(WORKER_COUNT)
        repeat(WORKER_COUNT) { idx ->
            Thread({
                try {
                    while (true) {
                        if (System.currentTimeMillis() > ctx.deadline) {
                            ctx.outOfTime.set(true)
                            break
                        }
                        val item = queue.poll() ?: break
                        try {
                            work(item)
                        } catch (e: Exception) {
                            log.error("MMK free-offer reverify failed on ${describe(item)}", e)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }, "$name-$idx").start()
        }
        latch.await()
    }
}
