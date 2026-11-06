package hr.workspace.boat4you.domains.pr.links

import hr.workspace.boat4you.domains.pr.dto.PrPlacementDto
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrPlacementRepository
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrPlacementService
import hr.workspace.boat4you.domains.pr.service.PrProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Checks whether a placement's article still links to us (PrPageFetcher -> PrLinkInspector) and stores the result.
 * Alerts compare against last_good_state (the last OK_FOLLOW / OK_NOFOLLOW seen, or a loss acknowledged by
 * clear-alert), not the previous check, so OK_FOLLOW -> 403 -> no link still alerts: last good OK_FOLLOW and now
 * LINK_MISSING / PAGE_GONE / OK_NOFOLLOW, or last good OK_NOFOLLOW and now LINK_MISSING / PAGE_GONE (alertReason
 * "OK_FOLLOW→LINK_MISSING"). A blocked page (BLOCKED_UNKNOWN: 403 / 429 / ...) never alerts and never changes
 * last_good_state. An alert is cleared only by hand (PrPlacementService.clearAlert). No DB transaction is held while
 * a page is fetched.
 */
@Service
class PrLinkCheckService(
    private val placements: PrPlacementRepository,
    private val placementService: PrPlacementService,
    private val fetcher: PrPageFetcher,
    private val props: PrProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(PrLinkCheckService::class.java)

    // Test seams (the project's `internal var` pattern): the pause between two requests to one host, the run limits.
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }
    internal var maxPerRun: Int = MAX_PER_RUN

    /** Stops a run before the 30-minute lock can expire (200 pages at 15 s each would take 50 minutes); the rest wait for tomorrow. */
    internal var runBudget: Duration = Duration.ofMinutes(25)

    fun check(placementId: Long): PrPlacementDto {
        val url = placement(placementId).articleUrl
        val fetch = fetcher.fetch(url)
        val inspection = fetch.html?.takeIf { fetch.status in 200..299 }?.let { PrLinkInspector.inspect(it, props.ourDomains, fetch.finalUrl) }
        val after = PrLinkInspector.classify(fetch, inspection)
        val good = if (after == PrLinkState.OK_FOLLOW || after == PrLinkState.OK_NOFOLLOW) after.name else ""
        // The UPDATE applies only while last_good_state is still the one the decision was made against; a clear-alert
        // or another check landing during the fetch makes it 0, and the decision is made again on the fresh row.
        repeat(RECORD_ATTEMPTS) {
            val current = placement(placementId)
            val lastGood = current.lastGoodState
            val raise = raisesAlert(lastGood, after)
            val reason = if (raise) "$lastGood$ARROW$after" else ""
            val updated = placements.recordCheck(placementId, lastGood?.name.orEmpty(), after.name, fetch.status, clock.instant(), good, raise, reason)
            if (updated == 1) {
                if (current.linkState != after || raise) {
                    log.info("PR link check: placement {} {} -> {} (HTTP {}){}", placementId, current.linkState, after, fetch.status, if (raise) " ALERT" else "")
                }
                return placementService.get(placementId)
            }
        }
        throw PrException.conflict("CONFLICT", "Placement $placementId changed during the check; check it again")
    }

    /**
     * One run of the daily job: placements published (or created, without a date) in the last [RECENT_DAYS] days every
     * time, older ones when not checked for [STALE_DAYS] days; at most [maxPerRun], [PAUSE_MS] between two requests to
     * one host. One placement failing never stops the run. Returns how many were checked.
     */
    fun checkDue(): Int {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZAGREB)
        val due = placements.findDue(today.minusDays(RECENT_DAYS), now.minus(STALE_DAYS, ChronoUnit.DAYS), maxPerRun)
        val deadline = System.nanoTime() + runBudget.toNanos()
        val lastDone = mutableMapOf<String, Long>()
        var checked = 0
        for ((i, placement) in due.withIndex()) {
            if (System.nanoTime() > deadline) {
                log.warn("PR link check: run budget of {} used up, {} placement(s) left for the next run", runBudget, due.size - i)
                break
            }
            val host = runCatching { URI(placement.articleUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
            host?.let { lastDone[it] }?.let { last ->
                val wait = PAUSE_MS - (System.nanoTime() - last) / NANOS_PER_MS
                if (wait > 0) sleeper(wait)
            }
            try {
                check(placement.id!!)
                checked++
            } catch (e: Exception) {
                log.error("PR link check: placement {} failed", placement.id, e)
            } finally {
                host?.let { lastDone[it] = System.nanoTime() }
            }
        }
        log.info("PR link check: {} of {} due placement(s) checked", checked, due.size)
        return checked
    }

    @Scheduled(cron = "\${pr.links.cron:0 30 5 * * *}", zone = "Europe/Zagreb")
    @SchedulerLock(name = "pr_link_check", lockAtMostFor = "PT30M")
    fun checkDueDaily() {
        runCatching { checkDue() }.onFailure { log.error("PR link check run failed", it) }
    }

    private fun placement(id: Long) = placements.findById(id).orElseThrow { PrException.notFound("PLACEMENT_NOT_FOUND", "Placement $id not found") }

    private fun raisesAlert(
        lastGood: PrLinkState?,
        after: PrLinkState,
    ): Boolean =
        when (lastGood) {
            PrLinkState.OK_FOLLOW -> after in setOf(PrLinkState.LINK_MISSING, PrLinkState.PAGE_GONE, PrLinkState.OK_NOFOLLOW)
            PrLinkState.OK_NOFOLLOW -> after == PrLinkState.LINK_MISSING || after == PrLinkState.PAGE_GONE
            else -> false
        }

    private companion object {
        const val MAX_PER_RUN = 200
        const val RECORD_ATTEMPTS = 3
        const val RECENT_DAYS = 14L
        const val STALE_DAYS = 7L
        const val PAUSE_MS = 2_000L
        const val NANOS_PER_MS = 1_000_000L

        /** "→" (U+2192), as in the alert reasons the admin shows. */
        const val ARROW = "→"
        val ZAGREB: ZoneId = ZoneId.of("Europe/Zagreb")
    }
}
