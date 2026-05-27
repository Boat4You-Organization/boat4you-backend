package hr.workspace.boat4you.domains.catalouge.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Keeps the `yacht_search_view` MATERIALIZED VIEW fresh.
 *
 * The view was materialized (R__1_03_yacht_search_view.sql) because the live
 * UNION join over ~475k offer rows made every /public/yachts search take >60s,
 * exhausting the Hikari pool and freezing the site. The matview makes searches
 * fast (~3.6s) but goes stale unless refreshed. This job rebuilds it on a short
 * interval so listings track partner sync (offers/availability/prices) within
 * one refresh window; the yacht detail + booking flow re-checks live anyway, so
 * a few minutes of listing staleness is acceptable.
 *
 * REFRESH ... CONCURRENTLY does NOT lock the matview (reads keep hitting the
 * old snapshot until the new one is ready), so user searches never block on a
 * refresh. It requires the unique index on `row_uid` (created in the migration)
 * and must run OUTSIDE a transaction — JdbcTemplate.execute runs in auto-commit,
 * so this method is deliberately not @Transactional.
 *
 * @Profile("data-sync") so only the scheduler node runs it (same as the sync
 * jobs); @SchedulerLock additionally guards against more than one node firing.
 */
@Profile("data-sync")
@Component
class SearchViewRefreshJob(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "refreshYachtSearchView", lockAtMostFor = "PT8M", lockAtLeastFor = "PT30S")
    fun refresh() {
        val start = System.currentTimeMillis()
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY public.yacht_search_view")
            log.info("Refreshed yacht_search_view in {} ms", System.currentTimeMillis() - start)
        } catch (e: Exception) {
            // Never let a failed refresh kill the scheduler thread — the matview
            // just keeps serving the previous snapshot until the next run.
            log.error("Failed to refresh yacht_search_view materialized view", e)
        }
    }
}
