package hr.workspace.boat4you.domains.calls.job

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Nika's own clock (Mario 14.11.2026): the dialler and the reconciler run on one thread of their own, never on the shared
 * @Scheduled pool. On 14.11. MMK answered 503 for an hour and its sync jobs held every scheduler thread, so a call due at
 * 11:00 waited. The sync jobs keep their pool untouched; this thread does a few DB reads a minute and one ElevenLabs request
 * per due call. The jobs are called through their Spring proxies, so their ShedLock locks still hold.
 */
@Profile("data-sync")
@Component
class CallJobsTimer(
    private val dispatch: CallDispatchJob,
    private val reconcile: CallReconcileJob,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val clock: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nika-calls").apply { isDaemon = true } }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // Aligned to the top of each minute, like the cron it replaces.
        val delayMs = MINUTE_MS - Instant.now().toEpochMilli() % MINUTE_MS
        clock.scheduleAtFixedRate(::tick, delayMs, MINUTE_MS, TimeUnit.MILLISECONDS)
        log.info("Nika call clock started on its own thread")
    }

    internal fun tick() {
        // Never let an exception end the fixed-rate schedule.
        runCatching { dispatch.run() }.onFailure { log.error("AI call dispatch tick failed", it) }
        if (Instant.now().epochSecond / 60 % RECONCILE_EVERY_MINUTES == 0L) {
            runCatching { reconcile.reconcile() }.onFailure { log.error("AI call reconcile tick failed", it) }
        }
    }

    @PreDestroy
    fun stop() {
        clock.shutdownNow()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val RECONCILE_EVERY_MINUTES = 10L
    }
}
