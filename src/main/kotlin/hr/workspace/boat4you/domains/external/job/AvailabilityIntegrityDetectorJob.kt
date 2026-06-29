package hr.workspace.boat4you.domains.external.job

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Daily read-only canary for availability-mirror integrity, so the "hidden free yacht" class of
 * defect can never silently recur (before this it was only ever found by accident). Runs on the
 * scheduler node at 06:40, after the 06:00 purge. Emits one INFO line always; WARN when a threshold
 * trips. READ-ONLY (JdbcTemplate counts) — it can never itself delete or mutate data. Mario 29.6.2026.
 *
 * Checks:
 *  A — CONTRADICTIONS: a yacht with a FREE offer overlapping a LIVE RESERVATION/SERVICE block on the
 *      same dates (the exact "shown free but hard-blocked" / "shown blocked but free" signature —
 *      the Vi La Ut case). After the natural-key reconcile + V9_23 cleanup, this should be ~0 and
 *      only transiently non-0 (clears day over day). A growing floor = key bug or stuck breaker.
 *  B1 — MAPPING-LESS blocking rows: RESERVATION/SERVICE with no reservation mapping (the 96k legacy
 *       backlog the reconcile drains). Must trend DOWN; an INCREASE = a new mapping-less source.
 *  B2 — DUPLICATE partner ids: one partner reservation id mapped to >1 reservation (the duplicate
 *       defect). Must reach 0 and stay 0; the upsert dup-guard prevents new ones.
 */
@Profile("data-sync")
@Component
class AvailabilityIntegrityDetectorJob(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        private const val CONTRADICTION_WARN_THRESHOLD = 25
    }

    @Scheduled(cron = "0 40 6 * * ?")
    @SchedulerLock(name = "availabilityIntegrityDetector", lockAtMostFor = "PT30M")
    fun check() {
        val contradictions =
            countOrZero(
                """
                SELECT count(*) FROM external_reservations r
                WHERE r.status IN ('RESERVATION', 'SERVICE')
                  AND r.date_to >= CURRENT_DATE
                  AND (r.option_expiration IS NULL OR r.option_expiration > now())
                  AND EXISTS (
                    SELECT 1 FROM offer o WHERE o.yacht_id = r.yacht_id AND o.status = 'FREE'
                      AND o.date_from < r.date_to AND o.date_to > r.date_from)
                """.trimIndent(),
            )
        val mappingLess =
            countOrZero(
                """
                SELECT count(*) FROM external_reservations r
                WHERE r.status IN ('RESERVATION', 'SERVICE')
                  AND NOT EXISTS (
                    SELECT 1 FROM external_mapping m WHERE m.system_id = r.id AND m.type = 'ExternalReservation')
                """.trimIndent(),
            )
        val duplicateIds =
            countOrZero(
                """
                SELECT count(*) FROM (
                  SELECT external_id FROM external_mapping WHERE type = 'ExternalReservation'
                  GROUP BY external_id, external_system_id HAVING count(*) > 1) t
                """.trimIndent(),
            )

        log.info(
            "Availability integrity: contradictions(free-vs-blocked)=$contradictions, " +
                "mapping-less RESERVATION/SERVICE=$mappingLess, duplicate partner-ids=$duplicateIds",
        )

        if (contradictions > CONTRADICTION_WARN_THRESHOLD) {
            log.warn(
                "⚠️ Availability integrity ALERT: $contradictions yacht-weeks shown FREE but hard-blocked " +
                    "(or vice-versa) — the natural-key reconcile should be clearing these. Sample yacht ids: " +
                    sampleContradictionYachts(),
            )
        }
    }

    private fun countOrZero(sql: String): Long =
        try {
            jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0L
        } catch (e: Exception) {
            log.warn("Availability integrity count query failed: ${e.message}")
            0L
        }

    private fun sampleContradictionYachts(): String =
        try {
            jdbcTemplate.queryForList(
                """
                SELECT DISTINCT r.yacht_id FROM external_reservations r
                WHERE r.status IN ('RESERVATION', 'SERVICE')
                  AND r.date_to >= CURRENT_DATE
                  AND (r.option_expiration IS NULL OR r.option_expiration > now())
                  AND EXISTS (
                    SELECT 1 FROM offer o WHERE o.yacht_id = r.yacht_id AND o.status = 'FREE'
                      AND o.date_from < r.date_to AND o.date_to > r.date_from)
                LIMIT 20
                """.trimIndent(),
                Long::class.java,
            ).joinToString(", ")
        } catch (e: Exception) {
            "n/a"
        }
}
