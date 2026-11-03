package hr.workspace.boat4you.domains.contactcenter.search

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs [SearchImport] every hour at :20 on the scheduler node (`data-sync` profile, one run at a time across nodes). Most runs
 * find nothing to do; the first runs after deploy fill the history ([SearchImport] takes a bounded number of days per run, a few
 * minutes). The short lock keeps a run cut by a cusma3 restart from holding the deploy gate's ShedLock check for long; an
 * interrupted day is simply imported again.
 */
@Profile("data-sync")
@Component
class SearchImportJob(
    private val import: SearchImport,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 20 * * * *", zone = "UTC")
    @SchedulerLock(name = "seoSearchImport", lockAtMostFor = "PT15M")
    fun run() {
        val result = import.run()
        if (result != NOTHING) log.info("Search engine import: {}", result)
    }

    private companion object {
        val NOTHING = SearchImport.Result(0, 0, 0, 0)
    }
}
