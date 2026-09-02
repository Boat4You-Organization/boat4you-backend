package hr.workspace.boat4you.domains.catalouge.job

import hr.workspace.boat4you.domains.catalouge.services.YachtSearchViewRefresher
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
 * and must run OUTSIDE a transaction — YachtSearchViewRefresher runs it in
 * auto-commit, so this method is deliberately not @Transactional.
 *
 * Change-aware skip: before refreshing, the job sums the cumulative
 * pg_stat_user_tables write counters (n_tup_ins + n_tup_upd + n_tup_del) of
 * every table the view reads. If the sum equals the one recorded after the
 * last successful refresh, nothing the matview depends on has been written
 * and the refresh is skipped. Semantics:
 *  - fail-open: a failed/null signature query → refresh anyway;
 *  - counters reset on a PG restart/crash → one extra refresh, never a missed one;
 *  - PG flushes backend stats within ~1 s of commit, so a write landing in the
 *    last second before a tick is picked up by the next tick, never lost;
 *  - `yacht_extras` is deliberately NOT in the list — the view stopped reading
 *    it (recommended_score no longer counts extras, R__1_03 2.9.2026);
 *  - lastSignature is per-JVM, which is correct because only the scheduler
 *    node (cusma3, data-sync profile) runs this cron. Daytime writes land in
 *    almost every window (cusma2's search-triggered offer syncs), so the skip
 *    mostly pays off at night/low traffic and as a safety net.
 *
 * @Profile("data-sync") so only the scheduler node runs it (same as the sync
 * jobs); @SchedulerLock additionally guards against more than one node firing.
 */
@Profile("data-sync")
@Component
class SearchViewRefreshJob(
    private val jdbcTemplate: JdbcTemplate,
    private val refresher: YachtSearchViewRefresher,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /** Write-counter sum recorded after the last successful refresh (null = never refreshed in this JVM). */
    private var lastSignature: Long? = null

    // 10-min cron is the safety net for partner-sync mutations (NauSys/MMK
    // batches mutate 100k+ offer rows; per-row refresh would be too chatty).
    // Admin actions (agency discount / recalc / etc.) trigger an on-demand
    // refresh via SearchViewRefreshService.requestRefresh() so the user sees
    // the effect in seconds, not minutes — independent of this cron.
    //
    // History: */2 → */5 (2026-06-17, cusma4 CPU: a 28 s refresh every 2 min
    // pinned ~23% of one core) → */10 (2026-09-02 fleet audit: 288 runs/day at
    // p50 47 s = 8 h of REFRESH per day and ~1.8 GB of temp files per run for
    // ~600 changed rows). Together with the in-memory diff and the slimmed view
    // (YachtSearchViewRefresher, R__1_03 2.9.2026) a refresh now costs ~10 s
    // and no temp I/O, 144×/day at most (fewer when the signature skip fires).
    // Trade-off: listing/facet staleness for partner-sync changes grows from
    // ≤5 to ≤10 min. Booking honesty is live via external_reservations, the
    // boat/booking step re-checks live and admin edits bypass this cron, so the
    // extra minutes are display-only lag (badges/prices), not a correctness change.
    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "refreshYachtSearchView", lockAtMostFor = "PT8M", lockAtLeastFor = "PT30S")
    fun refresh() {
        val signature = runCatching { jdbcTemplate.queryForObject(SIGNATURE_SQL, Long::class.java) }
            .onFailure { log.warn("yacht_search_view source signature query failed — refreshing anyway", it) }
            .getOrNull()
        if (signature != null && signature == lastSignature) {
            log.debug("yacht_search_view refresh skipped — no source-table writes since last run")
            return
        }
        try {
            val ms = refresher.refresh()
            lastSignature = signature
            log.info("Refreshed yacht_search_view in {} ms", ms)
        } catch (e: Exception) {
            // Never let a failed refresh kill the scheduler thread — the matview
            // just keeps serving the previous snapshot until the next run
            // (lastSignature is left unchanged so the next tick retries).
            log.error("Failed to refresh yacht_search_view materialized view", e)
        }
    }

    companion object {
        /** Every base table R__1_03_yacht_search_view.sql reads (both UNION ALL branches). */
        const val SIGNATURE_SQL = """
            SELECT COALESCE(SUM(n_tup_ins + n_tup_upd + n_tup_del), 0)
            FROM pg_stat_user_tables
            WHERE schemaname = 'public' AND relname IN
              ('offer', 'yacht', 'agency', 'location', 'model', 'manufacturer', 'yacht_charter_type', 'custom_yacht_details')
        """
    }
}
