package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.StaleMmkOfferCombo
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mirror image of [MmkStaleOfferReverifyService]: hides FREE 7-night weeks that MMK no longer sells.
 *
 * Why: since the agency-level sweep became upsert-only (20.7.2026) nothing removes a FREE week the partner has
 * withdrawn. An agency that has not published next season's price list keeps showing a full year of "free" weeks
 * at last year's prices (LA MAR, 21.9.2026: 10,375 rows on 189 yachts hidden by hand). Occupancy is mirrored by the
 * availability sync, but a withdrawn price list is not occupancy.
 *
 * Evidence rules, all learned the hard way (55de710 hid ~81k sellable weeks and was reverted in 58d4623):
 *  - The ONLY accepted evidence is the one call shape MMK answers reliably: exact dates, `flexibility=1`, a single
 *    `yachtId`. The agency feed and the per-yacht year call are NOT evidence.
 *  - Evidence scope == write scope: we ask about a 7-night week, we flip that 7-night week's rows and nothing else.
 *  - A call failure is "unknown", never "withdrawn".
 *  - A week is hidden only when two DAILY runs on different days both found it empty (`mmk_free_week_strike`,
 *    V9_59). Two calls 150 ms apart see the same 200-[] maintenance window; two days do not.
 *  - Breakers before any write: too few decided seasons, too many call failures, too large a share of the fleet
 *    empty, or too large a share of ONE agency empty (an agency whose feed broke looks exactly like one that
 *    withdrew its list; that decision goes to a human via the ERROR log).
 *  - A wall-clock budget, because `getOffers` retries 3x with a 60 s read timeout and an outage would otherwise
 *    run into the 16:40 availability slot.
 *
 * A hidden week stays a candidate of the nightly UNAVAILABLE->FREE reverifier (with a 7-day back-off), which turns
 * it back to FREE with the new price once the agency publishes it; the strike row is deleted the moment MMK quotes
 * the week again.
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

        // 21.9.2026 baseline: 199 of 6,929 yacht-seasons (2.9 %) fully unquoted. Three times that is not a normal day.
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

    private class Season(
        val yachtId: Long,
        val externalYachtId: Long,
        val agencyId: Long,
        val year: Int,
        val weeks: List<StaleMmkOfferCombo>,
    )

    private enum class Probe { QUOTED, EMPTY, UNKNOWN }

    class RunResult(
        val seasons: Int,
        val emptySeasons: Int,
        val firstStrikes: Int,
        val hiddenWeeks: Int,
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
            offerRepository.findFreeWeeklyMmkCombos(ExternalSystemEnum.MMK.value.toLong())
                .groupBy { it.yachtId to it.dateFrom.year }
                .map { (key, weeks) ->
                    Season(key.first, weeks.first().externalYachtId, weeks.first().agencyId, key.second, weeks.sortedBy { it.dateFrom })
                }
        log.info("MMK free-offer reverify: ${seasons.size} yacht-seasons, ${seasons.sumOf { it.weeks.size }} FREE weeks")
        if (seasons.isEmpty()) return RunResult(0, 0, 0, 0, 0, null)

        // Step 1 - probe every season (first, middle, last FREE week), no writes: the breakers need the whole picture.
        val probed = AtomicInteger()
        val empty = ConcurrentLinkedQueue<Season>()
        val decidedByAgency = ConcurrentHashMap<Long, AtomicInteger>()
        val emptyByAgency = ConcurrentHashMap<Long, AtomicInteger>()
        val unknown = AtomicInteger()
        runWorkers("mmk-free-probe", ConcurrentLinkedQueue(seasons), deadline, outOfTime) { season ->
            when (probeSeason(season)) {
                Probe.EMPTY -> {
                    empty.add(season)
                    decidedByAgency.getOrPut(season.agencyId) { AtomicInteger() }.incrementAndGet()
                    emptyByAgency.getOrPut(season.agencyId) { AtomicInteger() }.incrementAndGet()
                }
                Probe.QUOTED -> decidedByAgency.getOrPut(season.agencyId) { AtomicInteger() }.incrementAndGet()
                Probe.UNKNOWN -> unknown.incrementAndGet()
            }
            val done = probed.incrementAndGet()
            if (done % PROGRESS_LOG_EVERY == 0) log.info("MMK free-offer reverify probe progress: $done/${seasons.size}")
        }
        val decided = probed.get() - unknown.get()
        val emptyCount = empty.size
        val emptyShare = if (decided == 0) 0.0 else emptyCount.toDouble() / decided
        log.info(
            "MMK free-offer reverify probe: $decided decided, $emptyCount fully unquoted (${"%.1f".format(emptyShare * 100)} %), " +
                "${unknown.get()} unknown (call failures)${if (outOfTime.get()) ", STOPPED at the time budget" else ""}",
        )
        abortReason(seasons.size, decided, unknown.get(), emptyShare)?.let {
            log.error("MMK free-offer reverify ABORTED, nothing written: $it")
            return RunResult(seasons.size, emptyCount, 0, 0, 0, it)
        }
        if (emptyCount == 0) return RunResult(seasons.size, 0, 0, 0, 0, null)

        // Agencies whose whole fleet answers empty are not decided by this job.
        val suspectAgencies =
            emptyByAgency.filter { (agency, e) ->
                val d = decidedByAgency[agency]?.get() ?: 0
                d >= MIN_AGENCY_SEASONS_FOR_CAP && e.get().toDouble() / d > MAX_EMPTY_SHARE_PER_AGENCY
            }.keys
        suspectAgencies.forEach { agency ->
            log.error(
                "MMK free-offer reverify: agency $agency has ${emptyByAgency[agency]} of ${decidedByAgency[agency]} yacht-seasons unquoted " +
                    "- looks like a broken feed or a whole-fleet withdrawal; NOT hidden automatically, decide by hand",
            )
        }
        val toVerify =
            empty.filter { it.agencyId !in suspectAgencies }
                .sortedWith(compareBy({ it.yachtId }, { it.year }))
                .take(MAX_SEASONS_VERIFIED_PER_RUN)
        if (toVerify.size < emptyCount) {
            log.info("MMK free-offer reverify: verifying ${toVerify.size} of $emptyCount unquoted yacht-seasons this run (agency cap / per-run cap)")
        }

        // Step 2 - every FREE week of the unquoted seasons; a week is hidden only on its second strike on a later day.
        val firstStrikes = AtomicInteger()
        val hiddenWeeks = AtomicInteger()
        val hiddenRows = AtomicInteger()
        val seasonsDone = AtomicInteger()
        runWorkers("mmk-free-verify", ConcurrentLinkedQueue(toVerify), deadline, outOfTime) { season ->
            verifySeason(season, today, firstStrikes, hiddenWeeks, hiddenRows)
            val done = seasonsDone.incrementAndGet()
            if (done % 25 == 0) log.info("MMK free-offer reverify verify progress: $done/${toVerify.size} yacht-seasons")
        }
        log.info(
            "MMK free-offer reverify done: ${firstStrikes.get()} weeks got their first strike (hidden tomorrow if still empty), " +
                "${hiddenWeeks.get()} weeks hidden on their second strike (${hiddenRows.get()} offer rows)" +
                if (outOfTime.get()) "; STOPPED at the time budget, the rest runs tomorrow" else "",
        )
        return RunResult(seasons.size, emptyCount, firstStrikes.get(), hiddenWeeks.get(), hiddenRows.get(), null)
    }

    internal fun abortReason(
        total: Int,
        decided: Int,
        unknown: Int,
        emptyShare: Double,
    ): String? =
        when {
            decided < MIN_DECIDED_SEASONS -> "only $decided of $total yacht-seasons could be decided - partner unreachable"
            unknown.toDouble() / total > MAX_UNKNOWN_SHARE -> "$unknown of $total probes failed - partner degraded"
            emptyShare > MAX_EMPTY_SHARE -> "${"%.1f".format(emptyShare * 100)} % of decided yacht-seasons unquoted - MMK outage, not a fleet-wide withdrawal"
            else -> null
        }

    private fun probeSeason(season: Season): Probe {
        val w = season.weeks
        val samples = listOf(w.first(), w[w.size / 2], w.last()).distinctBy { it.dateFrom }
        for (week in samples) {
            when (ask(season.externalYachtId, week.dateFrom, week.dateTo)) {
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
        hiddenWeeks: AtomicInteger,
        hiddenRows: AtomicInteger,
    ) {
        var consecutiveErrors = 0
        val emptyToday = mutableListOf<StaleMmkOfferCombo>()
        val quotedToday = mutableListOf<StaleMmkOfferCombo>()
        for (week in season.weeks) {
            when (ask(season.externalYachtId, week.dateFrom, week.dateTo)) {
                Probe.EMPTY -> {
                    consecutiveErrors = 0
                    emptyToday += week
                }
                Probe.QUOTED -> {
                    consecutiveErrors = 0
                    quotedToday += week
                }
                Probe.UNKNOWN -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS_PER_YACHT) {
                        log.warn("MMK free-offer reverify: abandoning yacht=${season.yachtId} ${season.year} after $consecutiveErrors consecutive call failures")
                        break
                    }
                }
            }
        }
        // A quote is positive evidence with a price; it clears any earlier strike for that week.
        quotedToday.forEach { clearStrike(season.yachtId, it.dateFrom, it.dateTo) }
        if (emptyToday.isEmpty()) return

        val ran =
            yachtSyncMutex.runExclusiveYachtWrite(season.yachtId) {
                for (week in emptyToday) {
                    val firstEmptyOn = firstStrike(season.yachtId, week.dateFrom, week.dateTo)
                    if (firstEmptyOn == null) {
                        recordFirstStrike(season.yachtId, week.dateFrom, week.dateTo, today)
                        firstStrikes.incrementAndGet()
                    } else if (firstEmptyOn.isBefore(today)) {
                        val flipped = offerRepository.markWeekUnavailable(season.yachtId, week.dateFrom, week.dateTo)
                        recordHidden(season.yachtId, week.dateFrom, week.dateTo, today, flipped)
                        hiddenWeeks.incrementAndGet()
                        hiddenRows.addAndGet(flipped)
                    }
                    // firstEmptyOn == today: the same run already struck it (a restart), nothing to add.
                }
            }
        if (!ran) {
            log.info("MMK free-offer reverify: yacht=${season.yachtId} skipped, another sync is writing it")
            return
        }
        log.info(
            "MMK free-offer reverify: yacht=${season.yachtId} agency=${season.agencyId} ${season.year}: ${emptyToday.size} weeks not sold by MMK " +
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
        jdbcTemplate.update(
            "INSERT INTO mmk_free_week_strike (yacht_id, date_from, date_to, first_empty_on) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (yacht_id, date_from, date_to) DO NOTHING",
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

    /** Called when MMK quotes the week again — by this job's probe and by the UNAVAILABLE->FREE reverifier. */
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
                            log.error("MMK free-offer reverify failed on yacht=${season.yachtId} ${season.year}", e)
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
