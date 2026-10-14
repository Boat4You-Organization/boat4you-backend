package hr.workspace.boat4you.domains.external.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Retention of the real-price evidence tables (V9_82, DESIGN.md section 1.1):
 *
 *  1. `partner_offer_flip_log` rows older than [RUN_RETENTION_DAYS] (first, because they reference `reverify_run`);
 *  2. `reverify_run` rows started more than [RUN_RETENTION_DAYS] ago that no remaining flip-log row references;
 *  3. `partner_period_check` rows whose period ended more than [PERIOD_GRACE_DAYS] ago (nothing probes or shows a
 *     past period; the same grace as the dead-offer reaper).
 *
 * Purely time-based, no partner call, no offer row touched. Batched deletes through JdbcTemplate, each batch commits
 * on its own (RetentionReaperJob's lesson: one giant transaction never committed), with a per-run cap so a backlog
 * drains across nights. Daily 04:40 UTC on the scheduler node: a free slot after the 03:40 reaper and before the
 * 05:30 option purge. It deletes only old rows, so it never contends with a job writing today's evidence.
 */
@Profile("data-sync")
@Component
class RealPriceEvidenceRetentionJob(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    data class Summary(
        val flipLogDeleted: Long,
        val runsDeleted: Long,
        val periodChecksDeleted: Long,
    )

    @Scheduled(cron = "0 40 4 * * ?", zone = "UTC")
    @SchedulerLock(name = "realPriceEvidenceRetention", lockAtMostFor = "PT30M")
    fun run() {
        val s = purgeOnce()
        if (s.flipLogDeleted + s.runsDeleted + s.periodChecksDeleted > 0) {
            log.info(
                "Real-price evidence retention: partner_offer_flip_log -{}, reverify_run -{}, partner_period_check -{}",
                s.flipLogDeleted,
                s.runsDeleted,
                s.periodChecksDeleted,
            )
        }
    }

    /** One bounded pass; returns what was deleted. */
    fun purgeOnce(): Summary {
        val flips =
            deleteInBatches(
                "DELETE FROM partner_offer_flip_log WHERE id IN (SELECT id FROM partner_offer_flip_log " +
                    "WHERE created_at < now() - make_interval(days => $RUN_RETENTION_DAYS) LIMIT $BATCH)",
            )
        val runs =
            deleteInBatches(
                "DELETE FROM reverify_run WHERE id IN (SELECT r.id FROM reverify_run r " +
                    "WHERE r.started_at < now() - make_interval(days => $RUN_RETENTION_DAYS) " +
                    "AND NOT EXISTS (SELECT 1 FROM partner_offer_flip_log f WHERE f.run_id = r.id) LIMIT $BATCH)",
            )
        val checks =
            deleteInBatches(
                "DELETE FROM partner_period_check WHERE ctid IN (SELECT ctid FROM partner_period_check " +
                    "WHERE date_to < CURRENT_DATE - $PERIOD_GRACE_DAYS LIMIT $BATCH)",
            )
        return Summary(flips, runs, checks)
    }

    private fun deleteInBatches(sql: String): Long {
        var total = 0L
        repeat(MAX_BATCHES_PER_RUN) {
            val n = jdbcTemplate.update(sql)
            total += n
            if (n < BATCH) return total
        }
        return total
    }

    companion object {
        const val RUN_RETENTION_DAYS = 180
        const val PERIOD_GRACE_DAYS = 30
        private const val BATCH = 20_000
        private const val MAX_BATCHES_PER_RUN = 25
    }
}
