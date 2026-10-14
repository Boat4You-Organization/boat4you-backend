package hr.workspace.boat4you.domains.external.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Decides whether a dated + located public search may fire a live partner warm
 * (`ExternalSyncService.syncYachtOffers(start, end, locations)`), per partner.
 *
 * Mario, 5.9.2026: search is served from the DB, freshness comes from the scheduler.
 * Since then every range whose length is a multiple of 7 days skips the live warm for
 * BOTH partners ([shouldWarm]); only odd-length ranges still warm (the nightly warm
 * cost 1,362 freeYachtsSearch calls/day, half of them 429/502).
 *
 * What the nightly grids really store (checked 14.10.2026):
 * - NauSys: ReservationOptionsCombinationProvider builds Sat→Sat 7 days plus Sat→Sat of
 *   the agency season `minimalDuration`, nothing else (YachtGroupingProvider hard-codes
 *   Saturday). A 14/21/28-day range on a 7-night season, or any 7-day range that does not
 *   start on a Saturday, is NOT in the grid — the old claim "7/14/21/28 days from ANY start
 *   day" was false for NauSys. With `NAUSYS_WARM_LONG_RANGES=ON` ([shouldWarmNauSys]) those
 *   ranges warm NauSys live again; only Sat→Sat 7 nights (always in the grid) stays DB-only
 *   (Part 5 rule, 5.9.2026).
 * - MMK: the offer sweep stores the weekly ranges, so [shouldWarmMmk] is today's rule.
 *
 * Past ranges never warm. The per-yacht warm on the detail page is a different method and
 * is NOT gated here.
 */
object SearchWarmPolicy {
    const val WEEK_DAYS = 7L

    /** True for 7/14/21/28-day ranges regardless of the start day (and for a 0-day range, which the controller rejects anyway). */
    fun isWeeklyRange(
        start: LocalDate,
        end: LocalDate,
    ): Boolean = ChronoUnit.DAYS.between(start, end) % WEEK_DAYS == 0L

    /** True for exactly 7 nights starting on a Saturday: the one NauSys shape the nightly grid always stores. */
    fun isSaturdayWeek(
        start: LocalDate,
        end: LocalDate,
    ): Boolean = start.dayOfWeek == DayOfWeek.SATURDAY && ChronoUnit.DAYS.between(start, end) == WEEK_DAYS

    /**
     * A range that has already started cannot be sold, so there is nothing to refresh:
     * 5.9.2026 access-log sample — 43 of 53 non-weekly warms in 5 minutes were for PAST
     * dates (stale sister-site/crawler URLs). Those now serve whatever the DB has.
     */
    fun isPast(
        start: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean = start.isBefore(today)

    /** The rule since 5.9.2026 for both partners: not past and not a multiple of 7 days. */
    fun shouldWarm(
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean = !isWeeklyRange(start, end) && !isPast(start, today)

    /** MMK: unchanged, exactly [shouldWarm]. */
    fun shouldWarmMmk(
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Boolean = shouldWarm(start, end, today)

    /**
     * NauSys. [longRanges] = `NAUSYS_WARM_LONG_RANGES` is ON: not past and not Sat→Sat 7 nights.
     * OFF: exactly [shouldWarm] (today).
     */
    fun shouldWarmNauSys(
        start: LocalDate,
        end: LocalDate,
        longRanges: Boolean,
        today: LocalDate = LocalDate.now(),
    ): Boolean =
        if (longRanges) {
            !isPast(start, today) && !isSaturdayWeek(start, end)
        } else {
            shouldWarm(start, end, today)
        }

    /** True when at least one partner may be warmed; with [longRanges] OFF this is exactly [shouldWarm]. */
    fun shouldWarmAny(
        start: LocalDate,
        end: LocalDate,
        longRanges: Boolean,
        today: LocalDate = LocalDate.now(),
    ): Boolean = shouldWarmMmk(start, end, today) || shouldWarmNauSys(start, end, longRanges, today)
}
