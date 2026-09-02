package hr.workspace.boat4you.domains.external.mmk.job

import hr.workspace.boat4you.domains.external.mmk.service.MmkStaleOfferReverifyService
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("data-sync")
@Component
class MmkStaleReverifyJob(
    private val mmkStaleOfferReverifyService: MmkStaleOfferReverifyService,
    // One-shot trigger for the initial 54k-combo backlog drain: set on the scheduler node,
    // deploy/restart, remove after the run (a stray restart with the flag on is harmless —
    // post-backlog the run is near-empty and idempotent).
    @Value("\${MMK_STALE_REVERIFY_ON_BOOT:false}")
    private val reverifyOnBoot: Boolean,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // After the 06:30 offer sweep window: heals any week the availability sync marked
    // UNAVAILABLE whose partner reservation has since disappeared (cancellations), and any
    // other stray — per-yacht exact-date MMK calls are the only trusted "week is gone" signal.
    // 09:25 UTC (was 09:15): the 08:40 MMK availability run measured up to 09:20, so the two
    // MMK consumers no longer call the partner in parallel.
    @Scheduled(cron = "0 25 9 * * ?")
    @SchedulerLock(name = "mmkStaleOfferReverify", lockAtMostFor = "PT4H")
    fun runNightlyReverify() {
        log.info("Starting nightly MMK stale-offer reverify")
        val start = System.currentTimeMillis()
        mmkStaleOfferReverifyService.reverifyStaleUnavailableOffers()
        log.info("Nightly MMK stale-offer reverify took ${System.currentTimeMillis() - start} ms")
    }

    @EventListener(ApplicationReadyEvent::class)
    fun runOnBootIfRequested() {
        if (!reverifyOnBoot) return
        Thread({
            log.info("MMK_STALE_REVERIFY_ON_BOOT=true — starting backlog reverify")
            val start = System.currentTimeMillis()
            try {
                mmkStaleOfferReverifyService.reverifyStaleUnavailableOffers()
            } catch (e: Exception) {
                log.error("Boot-time MMK stale-offer reverify failed", e)
            }
            log.info("Boot-time MMK stale-offer reverify took ${System.currentTimeMillis() - start} ms")
        }, "mmk-stale-reverify-boot").start()
    }
}
