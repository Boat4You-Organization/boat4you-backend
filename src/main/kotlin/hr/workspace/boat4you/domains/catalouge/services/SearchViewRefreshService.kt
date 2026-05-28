package hr.workspace.boat4you.domains.catalouge.services

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refreshes the `yacht_search_view` MATERIALIZED VIEW on demand after an admin
 * mutation that changes data the listing query reads (agency discount, recalc'd
 * offer prices, agency active flag, yacht.exclude_discount flips, …).
 *
 * Why: the listing endpoint serves the denormalized snapshot from the matview
 * for speed (R__1_03_yacht_search_view.sql; partner offers/availability/prices
 * are baked in at refresh time). The 2-min cron in SearchViewRefreshJob is fine
 * for partner-sync mutations that touch 100k+ rows, but feels sluggish for
 * admin actions where the user expects an instant effect — e.g. clicks
 * "Recalculate prices" in UpdateAgencyModal and doesn't see the new prices in
 * /public/yachts for several minutes. This service schedules a refresh ~3s
 * after the mutation commits; multiple mutations within that window coalesce
 * into a single refresh (admin can hit recalc + tweak discount back-to-back
 * without spawning N refreshes).
 *
 * Concurrent-refresh safety: PostgreSQL serialises REFRESH MATERIALIZED VIEW
 * CONCURRENTLY (a second concurrent attempt errors out), so even if our cron
 * tick and on-demand path collide the loser just logs a warning — the next
 * tick picks up. The matview is never left in a bad state.
 *
 * NOT @Profile("data-sync"): must run on the api node (cusma2) where the
 * admin mutation endpoints land. The cron job (SearchViewRefreshJob) keeps its
 * own data-sync profile + @SchedulerLock so only the scheduler node fires the
 * periodic refresh.
 */
@Service
class SearchViewRefreshService(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val pending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "search-view-refresh").apply { isDaemon = true }
    }

    /**
     * Schedule a refresh ~3 s from now. If one is already scheduled or in
     * progress, do nothing — bursts of admin writes coalesce into a single
     * refresh. Returns immediately; the caller never blocks on the refresh
     * (the REFRESH itself runs outside any HTTP request thread so it doesn't
     * extend response time).
     */
    fun requestRefresh() {
        if (!pending.compareAndSet(false, true)) {
            return
        }
        executor.schedule({
            val start = System.currentTimeMillis()
            try {
                jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY public.yacht_search_view")
                log.info("on-demand refresh: yacht_search_view in {} ms", System.currentTimeMillis() - start)
            } catch (e: Exception) {
                log.warn("on-demand refresh of yacht_search_view failed — cron will catch up", e)
            } finally {
                pending.set(false)
            }
        }, 3, TimeUnit.SECONDS)
    }
}
