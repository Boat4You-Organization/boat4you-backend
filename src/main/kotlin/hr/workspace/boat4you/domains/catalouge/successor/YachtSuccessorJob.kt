package hr.workspace.boat4you.domains.catalouge.successor

import net.javacrumbs.shedlock.core.ClockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Daily recompute of yacht_successor (see [YachtSuccessorComputeService]). Scheduler node only (data-sync, cusma3).
 *
 * 07:55 UTC: boats are switched off by the partner syncs - the NauSys nightly yacht sync (23:20, the whole nightly block
 * measured up to 6 h 39 m, i.e. until ~06:00) and the MMK catalogue + yacht sync (06:00 / 06:10) - and the listing twins
 * (yacht_listing_twin) follow the offers within one 10-min search-view refresh. 07:55 comes after all of them, off the
 * :50 refresh tick and before the 08:00 charter facts. The run is one ~0.2 s SELECT plus a ~3k-row replace.
 *
 * At startup the table is filled once when it is empty (a fresh deploy), in the background and under the same lock, so
 * a new deploy does not wait until the next morning. A missing table (cusma3 deployed before cusma2 ran V9_73) is only
 * logged: the API node answers 1502 without a successor until the table exists.
 */
@Profile("data-sync")
@Component
class YachtSuccessorJob(
    private val computeService: YachtSuccessorComputeService,
    private val lockProvider: LockProvider,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = CRON, zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)
    fun recomputeDaily() {
        runCatching { computeService.recompute() }
            .onFailure { log.error("Daily yacht successor recompute failed - previous rows kept", it) }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun fillIfEmptyOnStartup() {
        val empty =
            runCatching { computeService.isEmpty() }
                .onFailure { log.warn("yacht_successor not readable at startup (V9_73 not applied yet?): {}", it.message) }
                .getOrNull()
        if (empty != true) return
        val lock =
            lockProvider
                .lock(LockConfiguration(ClockProvider.now(), LOCK_NAME, Duration.parse(LOCK_AT_MOST_FOR), Duration.ZERO))
                .orElse(null) ?: return
        Thread {
            try {
                computeService.recompute()
            } catch (e: Exception) {
                log.error("Startup yacht successor fill failed - the daily run retries", e)
            } finally {
                lock.unlock()
            }
        }.apply {
            name = "yacht-successor-startup"
            isDaemon = true
        }.start()
    }

    companion object {
        const val LOCK_NAME = "yachtSuccessorRecompute"

        /** 07:55 UTC daily - see the class KDoc for why this slot. */
        const val CRON = "0 55 7 * * *"

        /** The run is bounded by a 120 s statement_timeout; 15 min covers a stuck JVM. */
        const val LOCK_AT_MOST_FOR = "PT15M"
    }
}
