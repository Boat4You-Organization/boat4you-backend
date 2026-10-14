package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodKey
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Types
import java.time.LocalDate

/**
 * Undoes the status flips of one reverify / D3 run from `partner_offer_flip_log` (real-price DESIGN.md WS-B "Undo";
 * house rule: a data fix is code, never ad-hoc SQL). For every offer the run flipped (the first log row of that
 * offer in the run), `status` / `ext_status` go back to the logged old values — an OPTION_WAITING comes back as
 * OPTION_WAITING, never as FREE — but only while the row still looks exactly as the flip left it:
 *  - it is still UNAVAILABLE and its `ext_status` is unchanged (a partner sync since then wrote its own answer);
 *  - no live partner RESERVATION / SERVICE overlaps it (zombie escape: a row whose `option_expiration` has passed
 *    does not count, the same rule as the search hard block);
 *  - no `reservation_flow` points at it (a booking in progress);
 *  - no later flip-log row of another run exists for the offer (a later run flipped it again on its own evidence, or
 *    this run was already undone): re-posting an old undo never overrides newer evidence.
 * The guards are re-checked in each UPDATE, so a booking that lands during the undo still wins. For every period with
 * at least one restored row the hide record is cleared (`mmk_free_week_strike` row deleted for MMK,
 * `partner_period_check` strike and `hidden_on` cleared for both partners), so the search stops treating the period
 * as evidence of "taken" and a new two-day cycle starts if the partner is still silent. The undo is itself recorded:
 * a `reverify_run` row (job [ReverifyJobs.UNDO], the undone run in its note) and one flip-log row per restored offer
 * (reason `UNDO`, UNAVAILABLE -> the restored status), so the digest shows it and a repeated undo is a no-op.
 * One transaction.
 */
@Service
class PartnerFlipUndoService(
    private val jdbcTemplate: JdbcTemplate,
    private val periodChecks: PartnerPeriodCheckStore,
    private val runStore: ReverifyRunStore,
    transactionManager: PlatformTransactionManager,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)
    private val tx = TransactionTemplate(transactionManager)

    /** What an undo did; every flipped offer of the run is in exactly one bucket. */
    data class UndoResult(
        val runId: Long,
        val flippedOffers: Int,
        val restored: Int,
        val skippedNoLongerUnavailable: Int,
        val skippedChangedSince: Int,
        val skippedLiveReservation: Int,
        val skippedReservationFlow: Int,
        val skippedOfferGone: Int,
        val skippedSupersededByLaterRun: Int,
        val clearedPeriods: Int,
    )

    private class Flip(
        val offerId: Long,
        val sys: Int,
        val yachtId: Long,
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val oldStatus: String,
        val oldExtStatus: String?,
        val present: Boolean,
        val currentStatus: String?,
        val currentExtStatus: String?,
        val liveReservation: Boolean,
        val reservationFlow: Boolean,
        val superseded: Boolean,
    )

    private enum class Verdict { RESTORE, NOT_UNAVAILABLE, CHANGED, LIVE_RESERVATION, FLOW, GONE, SUPERSEDED }

    fun undoRun(runId: Long): UndoResult =
        tx.execute {
            val flips = jdbcTemplate.query(SELECT_FLIPS, ROW_MAPPER, runId)
            val verdicts = flips.associateWith(::verdict).toMutableMap()
            val toRestore = verdicts.filterValues { it == Verdict.RESTORE }.keys.toList()
            val updated =
                if (toRestore.isEmpty()) {
                    IntArray(0)
                } else {
                    jdbcTemplate.batchUpdate(
                        RESTORE,
                        toRestore.map { arrayOf<Any?>(it.oldStatus, it.oldExtStatus, it.offerId, it.oldExtStatus) },
                        intArrayOf(Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.VARCHAR),
                    )
                }
            // A guard that turned false between the read and the UPDATE (a booking landed): re-read why.
            toRestore.forEachIndexed { i, f -> if (updated[i] == 0) verdicts[f] = Verdict.CHANGED }
            val restored = verdicts.filterValues { it == Verdict.RESTORE }.keys
            if (restored.isNotEmpty()) recordUndo(runId, restored)
            val periods = restored.groupBy({ it.sys }, { PeriodKey(it.yachtId, it.dateFrom, it.dateTo) }).mapValues { it.value.distinct() }
            periods.forEach { (sys, keys) ->
                if (sys == ExternalSystemEnum.MMK.value) {
                    jdbcTemplate.batchUpdate(
                        "DELETE FROM mmk_free_week_strike WHERE yacht_id = ? AND date_from = ? AND date_to = ?",
                        keys.map { arrayOf<Any>(it.yachtId, it.dateFrom, it.dateTo) },
                        intArrayOf(Types.BIGINT, Types.DATE, Types.DATE),
                    )
                }
                periodChecks.clearStrikeBatch(ExternalSystemEnum.fromValue(sys), keys)
            }

            fun count(v: Verdict) = verdicts.values.count { it == v }
            UndoResult(
                runId = runId,
                flippedOffers = flips.size,
                restored = restored.size,
                skippedNoLongerUnavailable = count(Verdict.NOT_UNAVAILABLE),
                skippedChangedSince = count(Verdict.CHANGED),
                skippedLiveReservation = count(Verdict.LIVE_RESERVATION),
                skippedReservationFlow = count(Verdict.FLOW),
                skippedOfferGone = count(Verdict.GONE),
                skippedSupersededByLaterRun = count(Verdict.SUPERSEDED),
                clearedPeriods = periods.values.sumOf { it.size },
            ).also { log.info("Partner flip undo: {}", it) }
        }!!

    /** The undo's own run row and one `UNDO` flip-log row per restored offer, in the undo's transaction. */
    private fun recordUndo(
        undoneRunId: Long,
        restored: Collection<Flip>,
    ) {
        val undoRunId = runStore.start(ReverifyJobs.UNDO, ReverifyRunMode.WRITE)
        jdbcTemplate.batchUpdate(
            INSERT_UNDO_LOG,
            // old_ext_status = the row's ext_status now, which the RESTORE guard required to equal the logged one.
            restored.map { arrayOf<Any?>(undoRunId, it.sys, it.yachtId, it.dateFrom, it.dateTo, it.oldExtStatus, it.oldStatus, it.offerId) },
            intArrayOf(Types.BIGINT, Types.SMALLINT, Types.BIGINT, Types.DATE, Types.DATE, Types.VARCHAR, Types.VARCHAR, Types.BIGINT),
        )
        runStore.finish(undoRunId, abortedReason = "undo of run #$undoneRunId: ${restored.size} offers restored")
    }

    private fun verdict(f: Flip): Verdict =
        when {
            !f.present -> Verdict.GONE
            f.superseded -> Verdict.SUPERSEDED
            f.currentStatus != UNAVAILABLE -> Verdict.NOT_UNAVAILABLE
            f.currentExtStatus != f.oldExtStatus -> Verdict.CHANGED
            f.liveReservation -> Verdict.LIVE_RESERVATION
            f.reservationFlow -> Verdict.FLOW
            else -> Verdict.RESTORE
        }

    private companion object {
        const val UNAVAILABLE = "UNAVAILABLE"

        /** Live partner booking of the offer row `o` (zombie escape: an expired RESERVATION/SERVICE does not count). */
        const val LIVE_RESERVATION =
            "EXISTS (SELECT 1 FROM external_reservations r WHERE r.yacht_id = o.yacht_id " +
                "AND r.date_from < o.date_to AND r.date_to > o.date_from AND r.status IN ('RESERVATION','SERVICE') " +
                "AND (r.option_expiration IS NULL OR r.option_expiration > now()))"
        const val FLOW = "EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)"

        const val SELECT_FLIPS =
            "SELECT DISTINCT ON (f.offer_id) f.offer_id, f.external_system_id, f.yacht_id, f.date_from, f.date_to, " +
                "f.old_status, f.old_ext_status, o.id IS NOT NULL AS present, o.status AS current_status, " +
                "o.ext_status AS current_ext_status, " +
                "COALESCE($LIVE_RESERVATION, false) AS live_reservation, COALESCE($FLOW, false) AS reservation_flow, " +
                "EXISTS (SELECT 1 FROM partner_offer_flip_log l WHERE l.offer_id = f.offer_id AND l.id > f.id " +
                "AND l.run_id IS DISTINCT FROM f.run_id) AS superseded " +
                "FROM partner_offer_flip_log f LEFT JOIN offer o ON o.id = f.offer_id " +
                "WHERE f.run_id = ? ORDER BY f.offer_id, f.id"

        const val RESTORE =
            "UPDATE offer o SET status = ?, ext_status = ? WHERE o.id = ? AND o.status = '$UNAVAILABLE' " +
                "AND o.ext_status IS NOT DISTINCT FROM ? AND NOT $LIVE_RESERVATION AND NOT $FLOW"

        const val INSERT_UNDO_LOG =
            "INSERT INTO partner_offer_flip_log (run_id, job, reason, external_system_id, offer_id, yacht_id, date_from, " +
                "date_to, old_status, old_ext_status, old_client_price, new_status, decided_on) " +
                "SELECT ?, '${ReverifyJobs.UNDO}', 'UNDO', ?, o.id, ?, ?, ?, '$UNAVAILABLE', ?, o.client_price, ?, CURRENT_DATE " +
                "FROM offer o WHERE o.id = ?"

        val ROW_MAPPER =
            org.springframework.jdbc.core.RowMapper { rs, _ ->
                Flip(
                    offerId = rs.getLong("offer_id"),
                    sys = rs.getInt("external_system_id"),
                    yachtId = rs.getLong("yacht_id"),
                    dateFrom = rs.getObject("date_from", LocalDate::class.java),
                    dateTo = rs.getObject("date_to", LocalDate::class.java),
                    oldStatus = rs.getString("old_status"),
                    oldExtStatus = rs.getString("old_ext_status"),
                    present = rs.getBoolean("present"),
                    currentStatus = rs.getString("current_status"),
                    currentExtStatus = rs.getString("current_ext_status"),
                    liveReservation = rs.getBoolean("live_reservation"),
                    reservationFlow = rs.getBoolean("reservation_flow"),
                    superseded = rs.getBoolean("superseded"),
                )
            }
    }
}
