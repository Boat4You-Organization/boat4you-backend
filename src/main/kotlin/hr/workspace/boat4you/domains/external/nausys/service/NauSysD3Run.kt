package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.mmk.service.RunTally
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val d3Log = LoggerFactory.getLogger("hr.workspace.boat4you.domains.external.nausys.service.NauSysD3Run")

/**
 * One `reverify_run` (job NAUSYS_D3) for one NauSys grid pass (nightly, near-term or retry drain) under
 * `NAUSYS_D3_MODE` LOG or WRITE (DESIGN.md B2 "D3"). LEGACY never opens one: [open] returns null and
 * [NauSysYachtOfferSyncService.syncOffers] then runs today's disappearance pass byte for byte.
 *
 * Counters (`reverify_run`): calls = partner responses processed; empty = unpriced periods; unknown = rows left
 * unchanged for OUR mapping gap; first_strikes = strikes recorded (LOG: would-strike); hidden = flips (WRITE);
 * would_flip_rows = rows WRITE would flip right now (LOG). The per-agency / per-reason row counts are logged at
 * [finish] (one INFO line per agency) and their totals go into the run's note; agencies whose response tripped the
 * per-response unpriced breaker are escalated (ids only, admin digest).
 */
class NauSysD3Run internal constructor(
    val mode: NauSysD3Mode,
    internal val tally: RunTally,
    val today: LocalDate,
) {
    /** Why a row of the processed interval was not refreshed by the partner's answer. */
    enum class Reason {
        /** The same answer refreshed another route row of the yacht with these dates (positive evidence). */
        ROUTE_GONE,

        /** The partner returned these dates FREE but without a price. */
        UNPRICED,

        /** A row of the answer for these dates could not be mapped (our location mapping gap, not partner evidence). */
        MAPPING_GAP,

        /** The yacht is in the answer, but not for these dates. */
        NOT_RETURNED,
    }

    private val rows = ConcurrentHashMap<Pair<Long, Reason>, AtomicInteger>()
    private val escalated = ConcurrentHashMap.newKeySet<Long>()

    val runId: Long get() = tally.runId

    /** Set when the switch left WRITE during this pass (rollback by switch): no D3 writes from then on. */
    @Volatile
    private var downgradedTo: NauSysD3Mode? = null

    val writes: Boolean get() = mode == NauSysD3Mode.WRITE && downgradedTo == null

    /**
     * Rollback by switch inside a running pass: [current] = `NAUSYS_D3_MODE` now (60 s cached). Once a WRITE pass sees
     * anything else it stops writing for the rest of the pass and runs like LOG (today's writes plus counts, which is
     * what LEGACY writes too). Never upgrades a pass.
     */
    internal fun recheck(current: NauSysD3Mode) {
        if (mode != NauSysD3Mode.WRITE || current == NauSysD3Mode.WRITE || downgradedTo != null) return
        downgradedTo = current
        d3Log.warn("NauSys D3: NAUSYS_D3_MODE is $current now - run $runId stops its D3 writes, the rest of this pass runs today's disappearance pass")
    }

    internal fun count(
        agencyId: Long,
        reason: Reason,
        n: Int,
    ) {
        if (n > 0) rows.getOrPut(agencyId to reason) { AtomicInteger() }.addAndGet(n)
    }

    /**
     * The per-response unpriced breaker fired for this agency: no strikes from that response, admin decides.
     * @return true the first time in this run (the caller logs one ERROR line per agency, not one per interval).
     */
    internal fun escalate(agencyId: Long): Boolean = escalated.add(agencyId)

    internal fun escalatedAgencies(): Set<Long> = escalated.toSet()

    internal fun rowsFor(
        agencyId: Long,
        reason: Reason,
    ): Int = rows[agencyId to reason]?.get() ?: 0

    /** One partner answer processed; the counters are flushed every [FLUSH_EVERY] answers so a crash keeps them. */
    internal fun responseDone() {
        if (tally.calls.incrementAndGet() % FLUSH_EVERY == 0) tally.flush()
    }

    /** Closes the run: per-agency lines, the reason totals as the note, escalated agency ids. */
    fun finish(label: String) {
        val byAgency = rows.entries.groupBy({ it.key.first }, { it.key.second to it.value.get() })
        byAgency.entries
            .sortedByDescending { e -> e.value.sumOf { it.second } }
            .take(MAX_AGENCY_LINES)
            .forEach { (agency, counts) ->
                d3Log.info("NauSys D3 [$mode] $label agency $agency: ${counts.sortedBy { it.first }.joinToString(" ") { "${it.first}=${it.second}" }} rows")
            }
        val totals = Reason.entries.map { r -> r to rows.filterKeys { it.second == r }.values.sumOf { it.get() } }
        val note = "D3 $mode $label: " + totals.joinToString(" ") { "${it.first}=${it.second}" } + " rows, ${byAgency.size} agencies"
        val gaps = totals.first { it.first == Reason.MAPPING_GAP }.second
        if (gaps > 0) d3Log.warn("NauSys D3 [$mode] $label: $gaps rows left unchanged for a location mapping gap (our data, not partner evidence)")
        tally.finish(note, escalated.toList().sorted().ifEmpty { null })
        d3Log.info("NauSys D3 [$mode] $label end: run $runId ${tally.line()}; $note; escalated=${escalated.sorted()}")
    }

    companion object {
        private const val FLUSH_EVERY = 200
        private const val MAX_AGENCY_LINES = 100

        /** A LOG or WRITE run, or null for LEGACY (today's behaviour, no run row). */
        fun open(
            runStore: ReverifyRunStore,
            mode: NauSysD3Mode,
            today: LocalDate = LocalDate.now(),
        ): NauSysD3Run? {
            val runMode =
                when (mode) {
                    NauSysD3Mode.LEGACY -> return null
                    NauSysD3Mode.LOG -> ReverifyRunMode.LOG
                    NauSysD3Mode.WRITE -> ReverifyRunMode.WRITE
                }
            return NauSysD3Run(mode, RunTally(runStore, runStore.start(ReverifyJobs.NAUSYS_D3, runMode)), today)
        }
    }
}
