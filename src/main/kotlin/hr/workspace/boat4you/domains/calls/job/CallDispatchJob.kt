package hr.workspace.boat4you.domains.calls.job

import hr.workspace.boat4you.domains.calls.service.CallDispatcher
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** Every minute on the scheduler node: dial the due AI calls (nothing while calls.enabled is false). */
@Profile("data-sync")
@Component
class CallDispatchJob(
    private val dispatcher: CallDispatcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    @SchedulerLock(name = "callDispatch", lockAtMostFor = "PT5M")
    fun run() {
        runCatching { dispatcher.runOnce(Instant.now()) }
            .onSuccess { if (it > 0) log.info("AI calls started: {}", it) }
            .onFailure { log.error("AI call dispatch failed", it) }
    }
}
