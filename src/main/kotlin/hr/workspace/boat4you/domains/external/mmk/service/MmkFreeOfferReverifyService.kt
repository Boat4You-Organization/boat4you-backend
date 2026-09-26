package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
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
 */
@Service
class MmkFreeOfferReverifyService(
    private val offerRepository: OfferRepository,
    private val mmkRetryableClient: MmkRetryableClient,
    private val yachtSyncMutex: YachtSyncMutex,
    private val jdbcTemplate: JdbcTemplate,
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
    }

    /** One probed period. Copied off the JPA projection at once: ~470k proxies would hold ~250 MB for the whole run. */
    private class Period(
        val externalYachtId: Long,
        val agencyId: Long,
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
    )

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

    class RunResult(
        val seasons: Int,
        val emptySeasons: Int,
        val firstStrikes: Int,
        val hiddenPeriods: Int,
        val hiddenRows: Int,
        val aborted: String?,
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
        val deadline = System.currentTimeMillis() + RUN_BUDGET.toMillis()
        val outOfTime = AtomicBoolean(false)
        val seasons =
            offerRepository.findShownFreeMmkCombos(ExternalSystemEnum.MMK.value.toLong())
                .asSequence()
                .map { c -> c.yachtId to Period(c.externalYachtId, c.agencyId, c.dateFrom, c.dateTo) }
                .groupBy({ (yachtId, p) -> Triple(yachtId, p.dateFrom.year, ChronoUnit.DAYS.between(p.dateFrom, p.dateTo) == 7L) }, { it.second })
                .map { (key, periods) ->
                    Season(key.first, periods.first().externalYachtId, periods.first().agencyId, key.second, key.third, periods.sortedBy { it.dateFrom })
                }
        log.info(
            "MMK free-offer reverify: ${seasons.size} yacht-season groups (${seasons.count { it.weekly }} of weeks), " +
                "${seasons.sumOf { it.periods.size }} bookable-looking periods",
        )
        if (seasons.isEmpty()) return RunResult(0, 0, 0, 0, 0, null)

        // Step 1 - probe every group (its first, middle, last period), no writes: the breakers need the whole picture.
        val probed = AtomicInteger()
        val empty = ConcurrentLinkedQueue<Season>()
        // per shape: weekly=true / other lengths=false
        val decidedByShape = mapOf(true to AtomicInteger(), false to AtomicInteger())
        val emptyByShape = mapOf(true to AtomicInteger(), false to AtomicInteger())
        val decidedByAgency = ConcurrentHashMap<Pair<Long, Boolean>, AtomicInteger>()
        val emptyByAgency = ConcurrentHashMap<Pair<Long, Boolean>, AtomicInteger>()
        val unknown = AtomicInteger()
        runWorkers("mmk-free-probe", ConcurrentLinkedQueue(seasons), deadline, outOfTime) { season ->
            val agencyKey = season.agencyId to season.weekly
            when (probeSeason(season)) {
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
                "${unknown.get()} unknown (call failures)${if (outOfTime.get()) ", STOPPED at the time budget" else ""}",
        )
        // The week groups are the calibrated baseline: if they look wrong, the partner is sick and nothing is written.
        abortReason(seasons.size, decidedByShape.getValue(true).get(), unknown.get(), weeklyShare)?.let {
            log.error("MMK free-offer reverify ABORTED, nothing written: $it")
            return RunResult(seasons.size, empty.size, 0, 0, 0, it)
        }
        if (empty.isEmpty()) return RunResult(seasons.size, 0, 0, 0, 0, null)

        // Other lengths are judged on their own share once there are enough of them to have one; the weeks vouch that
        // the partner is healthy, so a high share here means MMK stopped quoting such periods, not that they are gone.
        val skipOtherLengths = decidedByShape.getValue(false).get() >= MIN_DECIDED_SEASONS && otherShare > MAX_EMPTY_SHARE
        if (skipOtherLengths) {
            log.error(
                "MMK free-offer reverify: ${"%.1f".format(otherShare * 100)} % of the non-weekly groups unquoted - " +
                    "not a plausible withdrawal; non-weekly periods NOT hidden this run, decide by hand",
            )
        }

        // Agencies whose whole fleet answers empty are not decided by this job - per shape, and a broken week feed
        // makes the agency's other lengths suspect as well.
        val suspect =
            emptyByAgency.filter { (key, e) ->
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
        fun isSuspect(s: Season) = (s.agencyId to s.weekly) in suspect || (s.agencyId to true) in suspect
        val toVerify =
            empty.filter { !isSuspect(it) && (it.weekly || !skipOtherLengths) }
                .sortedWith(compareBy({ it.yachtId }, { it.year }, { !it.weekly }))
                .take(MAX_SEASONS_VERIFIED_PER_RUN)
        if (toVerify.size < empty.size) {
            log.info("MMK free-offer reverify: verifying ${toVerify.size} of ${empty.size} unquoted groups this run (breakers / per-run cap)")
        }

        // Step 2 - every period of the unquoted groups; a period is hidden only on its second strike on a later day.
        val firstStrikes = AtomicInteger()
        val hiddenPeriods = AtomicInteger()
        val hiddenRows = AtomicInteger()
        val seasonsDone = AtomicInteger()
        runWorkers("mmk-free-verify", ConcurrentLinkedQueue(toVerify), deadline, outOfTime) { season ->
            verifySeason(season, today, firstStrikes, hiddenPeriods, hiddenRows)
            val done = seasonsDone.incrementAndGet()
            if (done % 25 == 0) log.info("MMK free-offer reverify verify progress: $done/${toVerify.size} groups")
        }
        log.info(
            "MMK free-offer reverify done: ${firstStrikes.get()} periods got their first strike (hidden tomorrow if still empty), " +
                "${hiddenPeriods.get()} periods hidden on their second strike (${hiddenRows.get()} offer rows)" +
                if (outOfTime.get()) "; STOPPED at the time budget, the rest runs tomorrow" else "",
        )
        return RunResult(seasons.size, empty.size, firstStrikes.get(), hiddenPeriods.get(), hiddenRows.get(), null)
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

    private fun probeSeason(season: Season): Probe {
        // A group is sampled from its own periods only: a quoted week says nothing about a long block MMK stopped
        // quoting (EMA 12210 2027), so weeks and other lengths never vouch for each other. A group quoted on one
        // sample but empty elsewhere is left alone (under-hide, never over-hide).
        val p = season.periods
        val samples = listOf(p.first(), p[p.size / 2], p.last()).distinctBy { it.dateFrom to it.dateTo }
        for (period in samples) {
            when (ask(season.externalYachtId, period.dateFrom, period.dateTo)) {
                Probe.QUOTED -> return Probe.QUOTED
                Probe.UNKNOWN -> return Probe.UNKNOWN
                Probe.EMPTY -> {}
            }
        }
        return Probe.EMPTY
    }

    private fun verifySeason(
        season: Season,
        today: LocalDate,
        firstStrikes: AtomicInteger,
        hiddenPeriods: AtomicInteger,
        hiddenRows: AtomicInteger,
    ) {
        var consecutiveErrors = 0
        val emptyToday = mutableListOf<Period>()
        val quotedToday = mutableListOf<Period>()
        for (period in season.periods) {
            when (ask(season.externalYachtId, period.dateFrom, period.dateTo)) {
                Probe.EMPTY -> {
                    consecutiveErrors = 0
                    emptyToday += period
                }
                Probe.QUOTED -> {
                    consecutiveErrors = 0
                    quotedToday += period
                }
                Probe.UNKNOWN -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS_PER_YACHT) {
                        log.warn("MMK free-offer reverify: abandoning yacht=${season.yachtId} ${season.label} after $consecutiveErrors consecutive call failures")
                        break
                    }
                }
            }
        }
        // A quote is positive evidence with a price; it clears any earlier strike for that period.
        quotedToday.forEach { clearStrike(season.yachtId, it.dateFrom, it.dateTo) }
        if (emptyToday.isEmpty()) return

        val ran =
            yachtSyncMutex.runExclusiveYachtWrite(season.yachtId) {
                for (period in emptyToday) {
                    val firstEmptyOn = firstStrike(season.yachtId, period.dateFrom, period.dateTo)
                    if (firstEmptyOn == null) {
                        recordFirstStrike(season.yachtId, period.dateFrom, period.dateTo, today)
                        firstStrikes.incrementAndGet()
                    } else if (firstEmptyOn.isBefore(today)) {
                        val flipped = offerRepository.markPeriodUnavailable(season.yachtId, period.dateFrom, period.dateTo)
                        if (flipped > 0) {
                            recordHidden(season.yachtId, period.dateFrom, period.dateTo, today, flipped)
                            hiddenPeriods.incrementAndGet()
                            hiddenRows.addAndGet(flipped)
                        } else {
                            // Nothing left to flip: a partner option or our own booking arrived since the candidate
                            // query, or another writer already hid it. No hide happened, so no hide is recorded.
                            clearStrike(season.yachtId, period.dateFrom, period.dateTo)
                        }
                    }
                    // firstEmptyOn == today: the same run already struck it (a restart), nothing to add.
                }
            }
        if (!ran) {
            log.info("MMK free-offer reverify: yacht=${season.yachtId} skipped, another sync is writing it")
            return
        }
        log.info(
            "MMK free-offer reverify: yacht=${season.yachtId} agency=${season.agencyId} ${season.label}: ${emptyToday.size} periods not sold by MMK " +
                "(${emptyToday.first().dateFrom}..${emptyToday.last().dateTo}), ${quotedToday.size} quoted",
        )
    }

    private fun firstStrike(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): LocalDate? =
        jdbcTemplate.query(
            "SELECT first_empty_on FROM mmk_free_week_strike WHERE yacht_id = ? AND date_from = ? AND date_to = ? AND hidden_on IS NULL",
            { rs, _ -> rs.getObject(1, LocalDate::class.java) },
            yachtId,
            dateFrom,
            dateTo,
        ).firstOrNull()

    private fun recordFirstStrike(
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

    private fun ask(
        externalYachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): Probe {
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
                if (response.isEmpty()) Probe.EMPTY else Probe.QUOTED
            } catch (e: Exception) {
                log.warn("MMK free-offer reverify call failed for externalYachtId=$externalYachtId $dateFrom..$dateTo: ${e.message}")
                Probe.UNKNOWN
            }
        Thread.sleep(CALL_PACING_MS)
        return result
    }

    private fun runWorkers(
        name: String,
        queue: ConcurrentLinkedQueue<Season>,
        deadline: Long,
        outOfTime: AtomicBoolean,
        work: (Season) -> Unit,
    ) {
        val latch = CountDownLatch(WORKER_COUNT)
        repeat(WORKER_COUNT) { idx ->
            Thread({
                try {
                    while (true) {
                        if (System.currentTimeMillis() > deadline) {
                            outOfTime.set(true)
                            break
                        }
                        val season = queue.poll() ?: break
                        try {
                            work(season)
                        } catch (e: Exception) {
                            log.error("MMK free-offer reverify failed on yacht=${season.yachtId} ${season.label}", e)
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
