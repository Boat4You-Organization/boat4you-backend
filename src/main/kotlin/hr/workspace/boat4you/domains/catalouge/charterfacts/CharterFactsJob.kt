package hr.workspace.boat4you.domains.catalouge.charterfacts

import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Daily recompute of the landing-page charter facts (see [CharterFactsComputeService]). Scheduler node only.
 *
 * 08:00 UTC, inside the 07:50-08:40 quiet window: AFTER the NauSys nightly yacht + offer sync (starts 23:20 and
 * measured up to 6 h 39 m, i.e. it can run until ~06:00 — a 04:20 run would read a half-refreshed offer grid under
 * the heaviest write load of the day on the PG that cusma2 shares), after the 03:25-03:40 retention reaper, the 05:30
 * expired-offer cleanup and the 06:00-07:20 MMK morning runs, so the facts are also the freshest of the day. The only
 * neighbour is the 08:00 MMK yacht-language backup, which runs only when the 07:20 run failed and touches few rows.
 *
 * After every run the age of the stored facts is checked: older than [STALE_AFTER] (the nightly guard kept refusing,
 * or the run keeps failing) is logged as ERROR every day until fixed, so stale facts cannot go unnoticed.
 */
@Profile("data-sync")
@Component
class CharterFactsJob(
    private val computeService: CharterFactsComputeService,
    private val lockProvider: LockProvider,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = CRON, zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    fun recomputeNightly() {
        runCatching { computeService.recompute() }
            .onFailure { log.error("Daily charter facts recompute failed — previous facts kept", it) }
        checkStaleness()
    }

    /** ERROR when the newest stored facts are older than [STALE_AFTER] (or the table is still empty). */
    fun checkStaleness(now: Instant = Instant.now()): Boolean {
        val latest = runCatching { computeService.latestComputedAt() }.getOrNull()
        val stale = latest == null || latest.isBefore(now.minus(STALE_AFTER))
        if (stale) {
            log.error(
                "Charter facts are STALE: newest computed_at = {} (limit {}). The public endpoint keeps serving old " +
                    "facts — check the recompute log; a legitimate large shrink needs POST /admin/charter-facts/recompute?force=true",
                latest,
                STALE_AFTER,
            )
        }
        return stale
    }

    /**
     * Manual trigger (admin endpoint): takes the SAME ShedLock lock as the cron, then runs in the background.
     * Returns false without starting anything when a run (cron or manual, on any node) holds the lock.
     * [force] skips the "less than half of the stored rows" guard (never the empty-result guard) — for a legitimate
     * large shrink, e.g. a shorter charter-facts.countries list or a big agency blocked.
     */
    fun recomputeInBackground(force: Boolean = false): Boolean {
        val lock =
            lockProvider
                .lock(LockConfiguration(ClockProvider.now(), LOCK_NAME, Duration.parse(LOCK_AT_MOST_FOR), Duration.ZERO))
                .orElse(null) ?: return false
        Thread {
            try {
                computeService.recompute(force)
            } catch (e: Exception) {
                log.error("Manual charter facts recompute failed — previous facts kept", e)
            } finally {
                lock.unlock()
            }
        }.apply {
            name = "manual-charter-facts"
            isDaemon = true
        }.start()
        return true
    }

    companion object {
        const val LOCK_NAME = "charterFactsRecompute"

        /** 08:00 UTC daily — see the class KDoc for why this slot. */
        const val CRON = "0 0 8 * * *"

        /** Facts older than this are reported as stale (one missed day is tolerated). */
        val STALE_AFTER: Duration = Duration.ofHours(48)

        /** The run is bounded by a 10-min statement_timeout per statement; 1 h covers a stuck JVM. */
        const val LOCK_AT_MOST_FOR = "PT1H"
    }
}
