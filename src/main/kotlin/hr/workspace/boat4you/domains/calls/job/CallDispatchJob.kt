package hr.workspace.boat4you.domains.calls.job

import hr.workspace.boat4you.domains.calls.service.CallDispatcher
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Instant

/** The scheduler node dials the due AI calls (nothing while calls.enabled is false); [CallJobsTimer] runs it every minute. */
@Profile("data-sync")
@Component
class CallDispatchJob(
    private val dispatcher: CallDispatcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Every minute, called by [CallJobsTimer] on its own thread (not @Scheduled: the sync jobs can hold that whole pool). */
    @SchedulerLock(name = "callDispatch", lockAtMostFor = "PT5M")
    fun run() {
        runCatching { dispatcher.runOnce(Instant.now()) }
            .onSuccess { if (it > 0) log.info("AI calls started: {}", it) }
            .onFailure { log.error("AI call dispatch failed", it) }
    }
}
