package hr.workspace.boat4you.domains.contactcenter.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs [IrisSearchBackfill] once, when `cc.backfill.enabled` is switched on (scheduler node, one run at a time across
 * nodes), and then switches the setting off again. Deliberately not `@Transactional`: the backfill commits per session and
 * the switch is turned off only after the last one, outside any transaction, so it is never rolled back with a wrapper.
 * A run that fails leaves the switch on and is retried at the next poll; the backfill is idempotent.
 */
@Profile("data-sync")
@Component
class IrisSearchBackfillJob(
    private val backfill: IrisSearchBackfill,
    private val settings: CcSettings,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cc.backfill.poll-ms:300000}", initialDelayString = "\${cc.backfill.poll-ms:300000}")
    @SchedulerLock(name = "ccIrisSearchBackfill", lockAtMostFor = "PT2H")
    fun run() {
        if (!settings.backfillEnabled) return
        val result = backfill.runAll()
        settings.disableBackfill()
        log.info("IRIS search backfill: {} sessions scanned, {} estimated searches written, cc.backfill.enabled switched off", result.sessions, result.rows)
    }
}
