package hr.workspace.boat4you.domains.external.job

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Daily admin digest of the partner reverifiers (real-price DESIGN.md WS-B "Run log"): the runs of the last 24 h
 * with their counters (LOG runs show would-upserts / would-flips), the status flips of the last 24 h per job and
 * reason, the agencies escalated in the last 7 days (the breakers refused to decide them), and the A2 criterion: the
 * number of future OPTION / OPTION_WAITING rows without a live hold, a booking in progress or partner evidence for
 * exactly their dates in the last 7 days (flip SEARCH_DEMOTE_STALE_OPTIONS=false when it stays below 1,000 on three
 * consecutive days).
 *
 * Admin-only mail through the existing admin mail path (SYSTEM_ADMIN addresses, internal template). Agency IDS only,
 * never a name: nothing here may reach a public surface. Read-only SQL. 07:00 UTC: after the night chain (NauSys
 * 23:20 nightly with availability and drain until ~06:15, MMK lang sweep 06:10/06:30) and before the 08:00 MMK backup.
 */
@Profile("data-sync")
@Component
class ReverifyDigestJob(
    private val jdbcTemplate: JdbcTemplate,
    private val emailService: EmailService,
    private val userRepository: UserRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Scheduled(cron = "0 0 7 * * ?", zone = "UTC")
    @SchedulerLock(name = "reverifyDigest", lockAtMostFor = "PT15M")
    fun run() {
        runCatching { sendDigest() }.onFailure { log.error("Reverify digest failed", it) }
    }

    fun sendDigest() {
        val body = buildDigest()
        val recipients = userRepository.findAllAdminEmailAddresses()
        if (recipients.isEmpty()) {
            log.warn("Reverify digest: no admin e-mail addresses - digest only in the log:\n{}", body)
            return
        }
        emailService.sendEmail(
            recipients = recipients,
            subject = "Provjere kod partnera - dnevni pregled (Boat4You)",
            templateName = TEMPLATE,
            variables = mapOf("reportBody" to body, "title" to "Provjere kod partnera - dnevni pregled"),
        )
        log.info("Reverify digest mailed to {} admin(s)", recipients.size)
    }

    fun buildDigest(): String =
        buildString {
            appendLine("PROVJERE KOD PARTNERA (MMK / NauSys) - zadnja 24 h")
            appendLine("Samo za admine: agencije su navedene po ID-u.")
            appendLine()
            appendRuns()
            appendLine()
            appendFlips()
            appendLine()
            appendEscalations()
            appendLine()
            appendA2()
        }

    private fun StringBuilder.appendRuns() {
        appendLine("RUNOVI (reverify_run, LOG = samo brojanje: upserts/would_flip su 'bi bilo')")
        val runs =
            jdbcTemplate.queryForList(
                "SELECT id, job, mode, started_at, ended_at, calls, quoted, empty, unknown, upserts, first_strikes, hidden_periods, " +
                    "hidden_rows, would_flip_rows, aborted_reason, escalated_agency_ids FROM reverify_run " +
                    "WHERE started_at > now() - INTERVAL '24 hours' ORDER BY started_at, id",
            )
        if (runs.isEmpty()) appendLine("  (nijedan run u zadnja 24 h)")
        runs.forEach { r ->
            appendLine(
                "  #${r["id"]} ${r["job"]} ${r["mode"]} ${r["started_at"]} -> ${r["ended_at"] ?: "JOS TRAJE / PREKINUT"}: " +
                    "calls=${r["calls"]} quoted=${r["quoted"]} empty=${r["empty"]} unknown=${r["unknown"]} upserts=${r["upserts"]} " +
                    "strikes=${r["first_strikes"]} hidden=${r["hidden_periods"]} (${r["hidden_rows"]} rows) wouldFlip=${r["would_flip_rows"]}",
            )
            r["aborted_reason"]?.let { appendLine("      prekid/napomena: $it") }
            ids(r["escalated_agency_ids"]).takeIf { it.isNotEmpty() }?.let { appendLine("      eskalirane agencije (id): ${it.joinToString(", ")}") }
        }
    }

    private fun StringBuilder.appendFlips() {
        appendLine("PROMJENE STATUSA (partner_offer_flip_log, zadnja 24 h; undo: POST /admin/reverify/runs/{id}/undo)")
        val flips =
            jdbcTemplate.queryForList(
                "SELECT run_id, job, reason, external_system_id, count(*) AS rows, count(DISTINCT (yacht_id, date_from, date_to)) AS periods " +
                    "FROM partner_offer_flip_log WHERE created_at > now() - INTERVAL '24 hours' " +
                    "GROUP BY run_id, job, reason, external_system_id ORDER BY run_id, job, reason",
            )
        if (flips.isEmpty()) appendLine("  (nema)")
        flips.forEach { f ->
            appendLine(
                "  run #${f["run_id"] ?: "-"} ${f["job"]} ${system(f["external_system_id"])} ${f["reason"]}: " +
                    "${f["periods"]} termina, ${f["rows"]} redaka -> UNAVAILABLE",
            )
        }
    }

    private fun StringBuilder.appendEscalations() {
        appendLine("ESKALIRANE AGENCIJE, zadnjih 7 dana (breakeri ih NISU sakrili - odluka je na adminu)")
        val rows =
            jdbcTemplate.queryForList(
                "SELECT e.agency_id, string_agg(DISTINCT r.job, ', ') AS jobs, max(r.started_at)::date AS last_seen, " +
                    "count(DISTINCT r.started_at::date) AS days " +
                    "FROM reverify_run r CROSS JOIN LATERAL unnest(r.escalated_agency_ids) AS e(agency_id) " +
                    "WHERE r.started_at > now() - INTERVAL '7 days' GROUP BY e.agency_id ORDER BY last_seen DESC, e.agency_id",
            )
        if (rows.isEmpty()) appendLine("  (nema)")
        rows.forEach { appendLine("  agencija ${it["agency_id"]}: ${it["jobs"]}, ${it["days"]} dana, zadnji put ${it["last_seen"]}") }
    }

    private fun StringBuilder.appendA2() {
        val bySystem = jdbcTemplate.queryForList(A2_CRITERION_SQL).associate { system(it["external_system_id"]) to (it["rows"] as Number).toLong() }
        val total = bySystem.values.sum()
        appendLine("A2 KRITERIJ (opcije bez dokaza partnera za tocno te datume u zadnjih 7 dana)")
        appendLine("  ukupno: $total ${bySystem.entries.joinToString(prefix = "(", postfix = ")") { "${it.key} ${it.value}" }}")
        appendLine(
            if (total < A2_THRESHOLD) {
                "  ispod $A2_THRESHOLD - ako tako ostane 3 dana zaredom: SEARCH_DEMOTE_STALE_OPTIONS=false"
            } else {
                "  iznad $A2_THRESHOLD - SEARCH_DEMOTE_STALE_OPTIONS ostaje true"
            },
        )
    }

    private fun ids(value: Any?): List<Long> =
        when (value) {
            null -> emptyList()
            is java.sql.Array -> (value.array as Array<*>).mapNotNull { (it as? Number)?.toLong() }
            is Array<*> -> value.mapNotNull { (it as? Number)?.toLong() }
            else -> emptyList()
        }

    private fun system(value: Any?): String = (value as? Number)?.toInt()?.let { v -> ExternalSystemEnum.entries.firstOrNull { it.value == v }?.name } ?: "?"

    companion object {
        const val TEMPLATE = "email/adminReverifyDigest"
        const val A2_THRESHOLD = 1000L

        /** DESIGN.md WS-A "A2 flip criterion", split per partner (read-only). */
        const val A2_CRITERION_SQL =
            """
            SELECT s.external_system_id, count(*) AS rows FROM offer o
            JOIN yacht y ON y.id = o.yacht_id
            JOIN agency_source s ON s.agency_id = y.agency_id AND s."primary"
            WHERE o.date_from >= CURRENT_DATE AND o.status IN ('OPTION','OPTION_WAITING')
              AND coalesce(o.ext_status,'') <> 'SYNTHETIC_OPTION'
              AND NOT EXISTS (SELECT 1 FROM external_reservations r WHERE r.yacht_id = o.yacht_id
                              AND r.date_from < o.date_to AND r.date_to > o.date_from
                              AND (r.status IN ('RESERVATION','SERVICE') OR (r.status = 'OPTION'
                                   AND (r.option_expiration IS NULL OR r.option_expiration > NOW() - INTERVAL '48 hours'))))
              AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id)
              AND NOT EXISTS (SELECT 1 FROM partner_period_check c
                              WHERE c.external_system_id = s.external_system_id AND c.yacht_id = o.yacht_id
                                AND c.date_from = o.date_from AND c.date_to = o.date_to
                                AND c.last_checked_on >= CURRENT_DATE - 7)
            GROUP BY s.external_system_id ORDER BY s.external_system_id
            """
    }
}
