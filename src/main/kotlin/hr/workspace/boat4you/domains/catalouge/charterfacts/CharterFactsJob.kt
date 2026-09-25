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

/**
 * Nightly recompute of the landing-page charter facts (see [CharterFactsComputeService]). Scheduler node only.
 *
 * 04:20 UTC: clear of the NauSys catalogue/offer runs (23:00-23:20 + follow-ups), the 03:25-03:40 voucher /
 * inquiry / retention-reaper jobs (the reaper deletes past offers — read after it), the 05:30 expired-offer cleanup
 * and the 06:00 MMK catalogue sync. The quarter-hourly NauSys retry and the 10-min matview refresh only touch a
 * few rows, and this job only reads the offer tables.
 */
@Profile("data-sync")
@Component
class CharterFactsJob(
    private val computeService: CharterFactsComputeService,
    private val lockProvider: LockProvider,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 20 4 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    fun recomputeNightly() {
        runCatching { computeService.recompute() }
            .onFailure { log.error("Nightly charter facts recompute failed — previous facts kept", it) }
    }

    /**
     * Manual trigger (admin endpoint): takes the SAME ShedLock lock as the cron, then runs in the background.
     * Returns false without starting anything when a run (cron or manual, on any node) holds the lock.
     */
    fun recomputeInBackground(): Boolean {
        val lock =
            lockProvider
                .lock(LockConfiguration(ClockProvider.now(), LOCK_NAME, Duration.parse(LOCK_AT_MOST_FOR), Duration.ZERO))
                .orElse(null) ?: return false
        Thread {
            try {
                computeService.recompute()
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

        /** The run is bounded by a 10-min statement_timeout per statement; 1 h covers a stuck JVM. */
        const val LOCK_AT_MOST_FOR = "PT1H"
    }
}
