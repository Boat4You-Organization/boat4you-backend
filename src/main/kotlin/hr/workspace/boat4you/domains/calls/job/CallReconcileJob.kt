package hr.workspace.boat4you.domains.calls.job

import hr.workspace.boat4you.domains.calls.service.CallReconciler
import hr.workspace.boat4you.domains.calls.service.CallRetention
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** Scheduler node: lost calls and webhook events every 10 minutes, recording and event retention every night. */
@Profile("data-sync")
@Component
class CallReconcileJob(
    private val reconciler: CallReconciler,
    private val retention: CallRetention,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */10 * * * *", zone = "UTC")
    @SchedulerLock(name = "callReconcile", lockAtMostFor = "PT9M")
    fun reconcile() {
        runCatching { reconciler.runOnce(Instant.now()) }.onFailure { log.error("AI call reconcile failed", it) }
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
    @SchedulerLock(name = "callRetention", lockAtMostFor = "PT30M")
    fun retention() {
        runCatching { retention.runOnce(Instant.now()) }.onFailure { log.error("AI call retention failed", it) }
    }
}
