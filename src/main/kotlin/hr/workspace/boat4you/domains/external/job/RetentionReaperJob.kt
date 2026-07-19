package hr.workspace.boat4you.domains.external.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Retention reaper (Mario 12.7.2026): time-based cleanup of rows that only ever
 * grow and are never read again — level #1 of the data-hygiene plan.
 *
 *  1. `service_call` — the partner-call audit log (every MMK/NauSys request since
 *     January, 6M rows / 1.9 GB, never queried after debugging). Rows older than
 *     [SERVICE_CALL_RETENTION_DAYS] are deleted.
 *  2. Dead offers — an offer whose charter period ended more than
 *     [OFFER_GRACE_DAYS] ago and that no reservation_flow ever referenced serves
 *     nothing: search only shows future dates and bookings pin their own flow's
 *     offer. Its children (offer_extras — the single biggest table in the DB —
 *     and offer_payment_plan) go with it in the same atomic statement.
 *  3. Expired `external_reservations` mirror rows (charter ended >
 *     [OFFER_GRACE_DAYS] ago) — nothing references them (verified: zero FKs).
 *  4. Yachts still sys_active under an INACTIVE agency (Mario 19.7.2026: "ne
 *     punimo se podacima koji nam ne trebaju"). The partner sync skips inactive
 *     agencies entirely, so their fleets stay lit forever (4163 at intro).
 *     Deactivation is REVERSIBLE: both yacht syncs set sysActive=true for every
 *     yacht the partner lists, so re-activating the agency relights its fleet
 *     on the next sync pass.
 *  5. FUTURE offers on inactive yachts — never searchable, never bookable, and
 *     the sync will not refresh them (inactive yacht/agency). Runs after #4 so
 *     a freshly darkened fleet's offers drain the same night. Offers referenced
 *     by a reservation_flow are never touched (same rule as #2).
 *
 * OWNERSHIP NOTE (12.7.2026 incident): #2 and #3 were previously owned by
 * `ReservationOfferService.deleteExpiredReservationsAndOffers` (06:00), which ran
 * all cleanup steps in ONE giant transaction that silently never committed —
 * identical "Purge mirror" orphan counts night after night (83,925 on both 7.7.
 * and 8.7.) proved every night's work rolled back, which is how a 26k-offer /
 * 78k-reservation backlog accumulated since January. That job now runs ONLY the
 * option purge; this reaper owns time-based retention, batched so every batch
 * commits on its own and partial progress can never be lost again.
 *
 * Safety model:
 *  - Offers referenced by ANY reservation_flow are NEVER touched (booking
 *    history stays intact); the FK would refuse anyway — belt and braces.
 *  - Set-based batched deletes via JdbcTemplate, each batch auto-committing in
 *    its own short transaction — no long ACCESS EXCLUSIVE-adjacent locks, no
 *    giant WAL spikes on cusma4 (the 29.6. idle-in-tx lesson).
 *  - Per-run caps bound one night's work; a large backlog (first runs) simply
 *    drains across nights. Progress is logged every run.
 *  - Purely time-based: this job does NOT talk to partners and never decides
 *    "the partner dropped this" — that is the (separate) mirror/reconcile layer.
 *
 * Space note: deleted rows become dead tuples; autovacuum makes the space
 * reusable so tables stop growing, but the files don't shrink on disk without
 * a VACUUM FULL/pg_repack (optional later, off-hours).
 */
@Profile("data-sync")
@Component
class RetentionReaperJob(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val SERVICE_CALL_RETENTION_DAYS = 60
        const val OFFER_GRACE_DAYS = 30

        /** Rows per DELETE batch. */
        private const val SERVICE_CALL_BATCH = 50_000
        private const val OFFER_BATCH = 5_000
        private const val EXTERNAL_RESERVATION_BATCH = 20_000
        private const val YACHT_DEACTIVATE_BATCH = 2_000

        /** Per-run ceilings so one night never bites off too much (backlog
         *  drains across nights instead of hammering cusma4 in one go). */
        private const val SERVICE_CALL_MAX_BATCHES_PER_RUN = 30 // ≤1.5M rows
        private const val OFFER_MAX_BATCHES_PER_RUN = 20 // ≤100k offers
        private const val EXTERNAL_RESERVATION_MAX_BATCHES_PER_RUN = 10 // ≤200k rows
        private const val YACHT_DEACTIVATE_MAX_BATCHES_PER_RUN = 5 // ≤10k yachts
        private const val INACTIVE_OFFER_MAX_BATCHES_PER_RUN = 20 // ≤100k offers
    }

    data class ReapSummary(
        val serviceCallsDeleted: Long,
        val offersDeleted: Long,
        val offerExtrasDeleted: Long,
        val offerPlansDeleted: Long,
        val externalReservationsDeleted: Long,
        val yachtsDeactivated: Long,
        val inactiveYachtOffersDeleted: Long,
        val backlogRemains: Boolean,
    )

    /** 03:40 nightly — after the NauSys catalogue window (01:00–~03:00), before
     *  the NauSys availability slot (04:00) and the MMK morning chain (06:00). */
    @Scheduled(cron = "0 40 3 * * ?")
    @SchedulerLock(name = "retentionReaper", lockAtMostFor = "PT2H")
    fun runNightly() {
        val summary = reapOnce()
        log.info(
            "Retention reaper: service_call -{} rows, offers -{} (+{} extras, +{} payment plans), " +
                "external_reservations -{}, yachts darkened {} (inactive agency), " +
                "inactive-yacht future offers -{}{}",
            summary.serviceCallsDeleted,
            summary.offersDeleted,
            summary.offerExtrasDeleted,
            summary.offerPlansDeleted,
            summary.externalReservationsDeleted,
            summary.yachtsDeactivated,
            summary.inactiveYachtOffersDeleted,
            if (summary.backlogRemains) " — backlog remains, continuing next night" else "",
        )
    }

    /** One bounded pass. Also invoked by the admin maintenance endpoint. */
    fun reapOnce(): ReapSummary {
        val serviceCalls = purgeOldServiceCalls()
        val offers = purgeDeadOffers()
        val externalReservations = purgeExpiredExternalReservations()
        // #4 before #5 so a fleet darkened tonight sheds its offers tonight.
        val yachtsDeactivated = deactivateYachtsUnderInactiveAgencies()
        val inactiveOffers = purgeFutureOffersOnInactiveYachts()
        return ReapSummary(
            serviceCallsDeleted = serviceCalls.first,
            offersDeleted = offers[0],
            offerExtrasDeleted = offers[1],
            offerPlansDeleted = offers[2],
            externalReservationsDeleted = externalReservations.first,
            yachtsDeactivated = yachtsDeactivated.first,
            inactiveYachtOffersDeleted = inactiveOffers[0],
            backlogRemains = serviceCalls.second || offers[3] > 0 || externalReservations.second ||
                yachtsDeactivated.second || inactiveOffers[3] > 0,
        )
    }

    /** @return (deleted rows, backlog remains) */
    private fun purgeOldServiceCalls(): Pair<Long, Boolean> {
        var total = 0L
        repeat(SERVICE_CALL_MAX_BATCHES_PER_RUN) {
            val deleted = jdbcTemplate.update(
                """
                DELETE FROM service_call
                WHERE id IN (
                    SELECT id FROM service_call
                    WHERE received_at < now() - make_interval(days => ?)
                    LIMIT ?
                )
                """.trimIndent(),
                SERVICE_CALL_RETENTION_DAYS,
                SERVICE_CALL_BATCH,
            )
            total += deleted
            if (deleted < SERVICE_CALL_BATCH) return total to false
        }
        return total to true
    }

    /**
     * One atomic statement per batch: pick victim offers, delete their children,
     * then the offers — all against the same snapshot. If a concurrent booking
     * somehow grabbed a victim offer mid-statement, the reservation_flow FK
     * aborts the batch (fail-safe: nothing half-deleted, retried next night).
     *
     * @return [offersDeleted, extrasDeleted, plansDeleted, backlogRemains(0/1)]
     */
    private fun purgeDeadOffers(): LongArray {
        var offers = 0L
        var extras = 0L
        var plans = 0L
        repeat(OFFER_MAX_BATCHES_PER_RUN) {
            // A concurrent booking grabbing a victim offer mid-statement aborts THIS batch
            // via the reservation_flow FK (fail-safe, nothing half-deleted). Don't let that
            // one lost batch kill the rest of the night's run — prior batches are committed.
            val row = runCatching {
                jdbcTemplate.queryForMap(
                """
                WITH victims AS (
                    SELECT o.id FROM offer o
                    WHERE o.date_to < CURRENT_DATE - make_interval(days => ?)
                      AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
                    LIMIT ?
                ),
                del_extras AS (
                    DELETE FROM offer_extras WHERE offer_id IN (SELECT id FROM victims) RETURNING 1
                ),
                del_plans AS (
                    DELETE FROM offer_payment_plan WHERE offer_id IN (SELECT id FROM victims) RETURNING 1
                ),
                del_offers AS (
                    DELETE FROM offer WHERE id IN (SELECT id FROM victims) RETURNING 1
                )
                SELECT (SELECT count(*) FROM del_offers)  AS offers,
                       (SELECT count(*) FROM del_extras)  AS extras,
                       (SELECT count(*) FROM del_plans)   AS plans
                """.trimIndent(),
                    OFFER_GRACE_DAYS,
                    OFFER_BATCH,
                )
            }.getOrElse { e ->
                log.warn("Dead-offer batch failed (kept prior batches, retrying next run)", e)
                return longArrayOf(offers, extras, plans, 1)
            }
            val batchOffers = (row["offers"] as Number).toLong()
            offers += batchOffers
            extras += (row["extras"] as Number).toLong()
            plans += (row["plans"] as Number).toLong()
            if (batchOffers < OFFER_BATCH) return longArrayOf(offers, extras, plans, 0)
        }
        return longArrayOf(offers, extras, plans, 1)
    }

    /**
     * Darken yachts still lit under an inactive (blacklisted/departed) agency.
     * The partner sync skips inactive agencies, so nothing else ever flips
     * these; search already filters them out via agency.active, this just
     * stops the DB carrying them as live inventory. Reversible — re-activating
     * the agency makes the next yacht sync set sysActive=true again.
     * @return (yachts deactivated, backlog remains)
     */
    private fun deactivateYachtsUnderInactiveAgencies(): Pair<Long, Boolean> {
        var total = 0L
        repeat(YACHT_DEACTIVATE_MAX_BATCHES_PER_RUN) {
            val updated = jdbcTemplate.update(
                """
                UPDATE yacht SET sys_active = false
                WHERE id IN (
                    SELECT y.id FROM yacht y
                    JOIN agency a ON a.id = y.agency_id
                    WHERE y.sys_active = true AND a.active = false
                    LIMIT ?
                )
                """.trimIndent(),
                YACHT_DEACTIVATE_BATCH,
            )
            total += updated
            if (updated < YACHT_DEACTIVATE_BATCH) return total to false
        }
        return total to true
    }

    /**
     * Future offers on inactive yachts — dead weight: search never returns
     * them (yacht/agency filters), the sync never refreshes them, and they
     * can't be booked. Same child-cleanup CTE + reservation_flow guard as
     * [purgeDeadOffers]; a booked offer's FK aborts the batch harmlessly.
     * @return [offersDeleted, extrasDeleted, plansDeleted, backlogRemains(0/1)]
     */
    private fun purgeFutureOffersOnInactiveYachts(): LongArray {
        var offers = 0L
        var extras = 0L
        var plans = 0L
        repeat(INACTIVE_OFFER_MAX_BATCHES_PER_RUN) {
            val row = runCatching {
                jdbcTemplate.queryForMap(
                    """
                    WITH victims AS (
                        SELECT o.id FROM offer o
                        JOIN yacht y ON y.id = o.yacht_id
                        WHERE y.sys_active = false
                          AND o.date_to >= CURRENT_DATE
                          AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
                        LIMIT ?
                    ),
                    del_extras AS (
                        DELETE FROM offer_extras WHERE offer_id IN (SELECT id FROM victims) RETURNING 1
                    ),
                    del_plans AS (
                        DELETE FROM offer_payment_plan WHERE offer_id IN (SELECT id FROM victims) RETURNING 1
                    ),
                    del_offers AS (
                        DELETE FROM offer WHERE id IN (SELECT id FROM victims) RETURNING 1
                    )
                    SELECT (SELECT count(*) FROM del_offers)  AS offers,
                           (SELECT count(*) FROM del_extras)  AS extras,
                           (SELECT count(*) FROM del_plans)   AS plans
                    """.trimIndent(),
                    OFFER_BATCH,
                )
            }.getOrElse { e ->
                log.warn("Inactive-yacht offer batch failed (kept prior batches, retrying next run)", e)
                return longArrayOf(offers, extras, plans, 1)
            }
            val batchOffers = (row["offers"] as Number).toLong()
            offers += batchOffers
            extras += (row["extras"] as Number).toLong()
            plans += (row["plans"] as Number).toLong()
            if (batchOffers < OFFER_BATCH) return longArrayOf(offers, extras, plans, 0)
        }
        return longArrayOf(offers, extras, plans, 1)
    }

    /** Mirror rows for charters that ended > [OFFER_GRACE_DAYS] ago. Zero FKs reference
     *  this table; orphaned external_mappings are cleaned by the option purge's own pass.
     *  @return (deleted rows, backlog remains) */
    private fun purgeExpiredExternalReservations(): Pair<Long, Boolean> {
        var total = 0L
        repeat(EXTERNAL_RESERVATION_MAX_BATCHES_PER_RUN) {
            val deleted = jdbcTemplate.update(
                """
                DELETE FROM external_reservations
                WHERE id IN (
                    SELECT id FROM external_reservations
                    WHERE date_to < CURRENT_DATE - make_interval(days => ?)
                    LIMIT ?
                )
                """.trimIndent(),
                OFFER_GRACE_DAYS,
                EXTERNAL_RESERVATION_BATCH,
            )
            total += deleted
            if (deleted < EXTERNAL_RESERVATION_BATCH) return total to false
        }
        return total to true
    }
}
