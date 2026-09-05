package hr.workspace.boat4you.domains.external.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Decides whether a dated + located public search may fire a live partner warm
 * (`ExternalSyncService.syncYachtOffers(start, end, locations)`).
 *
 * Mario, 5.9.2026: search is served from the DB, freshness comes from the scheduler.
 * The nightly NauSys grid (ReservationOptionsCombinationProvider: Sat→Sat 7 d, the
 * agency minimalDuration and every allowed check-in/check-out pair, 18 months ahead)
 * and the MMK offer sweep already store every weekly interval — 7/14/21/28 days from
 * ANY start day — so a live freeYachtsSearch for those ranges only spends the NauSys
 * quota the scheduler needs (1,362 warms/day, half of them 429/502). Only odd-length
 * ranges, which the grid does not carry, still warm. Applies to BOTH NauSys and MMK;
 * the per-yacht warm on the detail page is a different method and is NOT gated here.
 */
object SearchWarmPolicy {
    const val WEEK_DAYS = 7L

    /** True for 7/14/21/28-day ranges regardless of the start day (and for a 0-day range, which the controller rejects anyway). */
    fun isWeeklyRange(
        start: LocalDate,
        end: LocalDate,
    ): Boolean = ChronoUnit.DAYS.between(start, end) % WEEK_DAYS == 0L

    /**
     * A range that has already started cannot be sold, so there is nothing to refresh:
     * 5.9.2026 access-log sample — 43 of 53 non-weekly warms in 5 minutes were for PAST
     * dates (stale sister-site/crawler URLs). Those now serve whatever the DB has.
     */
    fun isPast(
        start: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean = start.isBefore(today)

    fun shouldWarm(
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean = !isWeeklyRange(start, end) && !isPast(start, today)
}
