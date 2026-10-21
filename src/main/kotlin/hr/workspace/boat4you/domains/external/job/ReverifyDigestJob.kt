package hr.workspace.boat4you.domains.external.job

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkOptionVerifyMode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunMode
import hr.workspace.boat4you.domains.external.nausys.service.NauSysReverifyLimits
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Daily admin digest of the partner reverifiers (real-price DESIGN.md WS-B "Run log"): the runs of the last 24 h
 * with their counters (LOG runs show would-upserts / would-flips; a NAUSYS_REVERIFY LOG run also the
 * [NauSysReverifyLimits.capP3] its step 2 is held to), the status flips of the last 24 h per run, the job that wrote
 * them and reason, with the old statuses and the agency ids they hit, the agencies escalated in the last 7 days (the
 * breakers refused to decide them), and the A2 criterion: the
 * number of future OPTION / OPTION_WAITING rows without a live hold, a booking in progress or partner evidence for
 * exactly their dates in the last 7 days (flip SEARCH_DEMOTE_STALE_OPTIONS=false when it stays below 1,000 on three
 * consecutive days).
 *
 * Mailed only when something needs a human (Mario 21.10.2026: "kada vidiš grešku mi samo pošalji"): [digestTriggers]
 * names the reasons, they head the body under [REASONS_HEADING] and the subject gets [SUBJECT_PREFIX]. A day without a
 * reason stays in the application log only (INFO, full body).
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
    private val switches: RealPriceSwitches,
    private val nauSysLimits: NauSysReverifyLimits,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * A2 totals of this JVM's digests by UTC day, for the A2 trigger. Not persisted (the digest stays read-only SQL): a
     * restart of the scheduler node starts the count again, so the third-day mail can come up to two days later.
     */
    private val a2ByDay = ConcurrentSkipListMap<LocalDate, Long>()

    @Scheduled(cron = "0 0 7 * * ?", zone = "UTC")
    @SchedulerLock(name = "reverifyDigest", lockAtMostFor = "PT15M")
    fun run() {
        runCatching { sendDigest() }.onFailure { log.error("Reverify digest failed", it) }
    }

    fun sendDigest() {
        val digest = composeDigest()
        if (digest.triggers.isEmpty()) {
            log.info("Reverify digest: nothing to report, mail skipped")
            log.info("Reverify digest (log only):\n{}", digest.text)
            return
        }
        val recipients = userRepository.findAllAdminEmailAddresses()
        if (recipients.isEmpty()) {
            log.warn("Reverify digest: no admin e-mail addresses - digest only in the log:\n{}", digest.text)
            return
        }
        emailService.sendEmail(
            recipients = recipients,
            subject = SUBJECT_PREFIX + SUBJECT,
            templateName = TEMPLATE,
            variables = mapOf("reportBody" to digest.text, "title" to "Provjere kod partnera - dnevni pregled"),
        )
        log.info("Reverify digest mailed to {} admin(s), {} reason(s)", recipients.size, digest.triggers.size)
    }

    /** The digest as it would go out now: the report, headed by the reasons to mail it when there are any. */
    fun buildDigest(): String = composeDigest().text

    private fun composeDigest(): Digest {
        val runs = jdbcTemplate.queryForList(RUNS_SQL)
        val flips = jdbcTemplate.queryForList(FLIPS_SQL)
        val escalations = jdbcTemplate.queryForList(ESCALATIONS_SQL)
        val a2BySystem = jdbcTemplate.queryForList(A2_CRITERION_SQL).associate { system(it["external_system_id"]) to (it["rows"] as Number).toLong() }
        val today = LocalDate.now(ZoneOffset.UTC)
        a2ByDay[today] = a2BySystem.values.sum()
        a2ByDay.headMap(today.minusDays(A2_HISTORY_DAYS)).clear()
        val body =
            buildString {
                appendLine("PROVJERE KOD PARTNERA (MMK / NauSys) - zadnja 24 h")
                appendLine("Samo za admine: agencije su navedene po ID-u.")
                appendLine()
                appendRuns(runs)
                appendLine()
                appendFlips(flips)
                appendLine()
                appendEscalations(escalations)
                appendLine()
                appendA2(a2BySystem)
            }
        val facts =
            DigestFacts(
                runs = runs.map(::runFact),
                flips = flips.map(::flipFact),
                escalations = escalations.map(::escalationFact),
                expectedJobs = expectedDailyJobs(switches.snapshot()),
                a2ByDay = a2ByDay.toMap(),
                today = today,
            )
        return Digest(body, digestTriggers(facts))
    }

    private fun StringBuilder.appendRuns(runs: List<Map<String, Any?>>) {
        appendLine("RUNOVI (reverify_run, LOG = samo brojanje: upserts/would_flip su 'bi bilo')")
        if (runs.isEmpty()) appendLine("  (nijedan run u zadnja 24 h)")
        runs.forEach { r ->
            appendLine(
                "  #${r["id"]} ${r["job"]} ${r["mode"]} ${r["started_at"]} -> ${r["ended_at"] ?: "JOS TRAJE / PREKINUT"}: " +
                    "calls=${r["calls"]} quoted=${r["quoted"]} empty=${r["empty"]} unknown=${r["unknown"]} upserts=${r["upserts"]} " +
                    "strikes=${r["first_strikes"]} hidden=${r["hidden_periods"]} (${r["hidden_rows"]} rows) wouldFlip=${r["would_flip_rows"]}" +
                    capNote(r),
            )
            r["aborted_reason"]?.let { appendLine("      prekid/napomena: $it") }
            ids(r["escalated_agency_ids"]).takeIf { it.isNotEmpty() }?.let { appendLine("      eskalirane agencije (id): ${it.joinToString(", ")}") }
        }
    }

    /**
     * NAUSYS_REVERIFY LOG: step 2 asks at most [NauSysReverifyLimits.capP3] open-strike periods a run and LOG takes none
     * off, so the would-flip count stays at about that cap every day (21.10.2026: ~2,090) - the cap, not a new anomaly.
     */
    private fun capNote(r: Map<String, Any?>): String =
        if (r["job"] == ReverifyJobs.NAUSYS_REVERIFY && r["mode"] == ReverifyRunMode.LOG.name) {
            " (korak 2 ograničen na capP3=${nauSysLimits.capP3} perioda/dan; LOG ništa ne skida, pa broj stoji uz taj limit)"
        } else {
            ""
        }

    private fun StringBuilder.appendFlips(flips: List<Map<String, Any?>>) {
        appendLine("PROMJENE STATUSA (partner_offer_flip_log, zadnja 24 h; undo: POST /admin/reverify/runs/{id}/undo)")
        appendLine("  job = tko je promijenio status: MMK_FREE = korak 2 (grupe brod/godina/oblik bez ijedne kvote), MMK_OPTION = koraci 2c/2b (opcije, otvoreni udarci)")
        if (flips.isEmpty()) appendLine("  (nema)")
        flips.forEach { f ->
            appendLine(
                "  run #${f["run_id"] ?: "-"} ${f["job"]} ${system(f["external_system_id"])} ${f["reason"]}: " +
                    "${f["periods"]} termina, ${f["rows"]} redaka -> UNAVAILABLE",
            )
            val more = num(f["agencies"]) - FLIP_AGENCIES_SHOWN
            appendLine(
                "      prije: ${f["old_statuses"] ?: "?"}; agencije (id: redaka): ${f["top_agencies"] ?: "-"}" +
                    if (more > 0) " (+$more)" else "",
            )
        }
    }

    private fun StringBuilder.appendEscalations(rows: List<Map<String, Any?>>) {
        appendLine("ESKALIRANE AGENCIJE, zadnjih 7 dana (breakeri ih NISU sakrili - odluka je na adminu)")
        appendLine("  job = run čiji ih je breaker eskalirao; MMK_FREE eskalacija vrijedi i za korake 2c/2b (MMK_OPTION)")
        if (rows.isEmpty()) appendLine("  (nema)")
        rows.forEach { appendLine("  agencija ${it["agency_id"]}: ${it["jobs"]}, ${it["days"]} dana, zadnji put ${it["last_seen"]}") }
    }

    private fun StringBuilder.appendA2(bySystem: Map<String, Long>) {
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

    private fun runFact(r: Map<String, Any?>) =
        RunFact(
            id = num(r["id"]),
            job = r["job"].toString(),
            mode = r["mode"].toString(),
            ended = r["ended_at"] != null,
            calls = num(r["calls"]),
            quoted = num(r["quoted"]),
            hiddenPeriods = num(r["hidden_periods"]),
            wouldFlipRows = num(r["would_flip_rows"]),
            note = r["aborted_reason"]?.toString(),
        )

    private fun flipFact(f: Map<String, Any?>) = FlipFact(job = f["job"].toString(), rows = num(f["rows"]))

    private fun escalationFact(e: Map<String, Any?>) =
        EscalationFact(
            agencyId = num(e["agency_id"]),
            days = num(e["days"]),
            inLast24h = e["in_last_24h"] == true,
            inPreviousDays = e["in_previous_days"] == true,
        )

    private fun num(value: Any?): Long = (value as? Number)?.toLong() ?: 0L

    /** The report [body] (layout unchanged) and the reasons to mail it; [text] is what is mailed or logged. */
    class Digest(
        val body: String,
        val triggers: List<String>,
    ) {
        val text: String get() = withReasons(body, triggers)
    }

    /** One `reverify_run` row of the last 24 h, as the triggers read it. */
    data class RunFact(
        val id: Long,
        val job: String,
        val mode: String,
        val ended: Boolean,
        val calls: Long,
        val quoted: Long,
        val hiddenPeriods: Long,
        val wouldFlipRows: Long,
        /** `aborted_reason`: a breaker, a stop or a routine closing summary (see [isRoutineNote]). */
        val note: String?,
    )

    /** One flip-log group of the last 24 h (per run, job, reason and partner): its job and row count. */
    data class FlipFact(
        val job: String,
        val rows: Long,
    )

    /** One agency escalated in the last 7 days: on how many days, and whether in the last 24 h / the 6 days before. */
    data class EscalationFact(
        val agencyId: Long,
        val days: Long,
        val inLast24h: Boolean,
        val inPreviousDays: Boolean,
    )

    /** Everything [digestTriggers] decides on; built from the digest queries, or by hand in a test. */
    data class DigestFacts(
        val runs: List<RunFact>,
        val flips: List<FlipFact>,
        val escalations: List<EscalationFact>,
        /** Jobs the current switches run every day ([expectedDailyJobs]); one without a run in the last 24 h is missing. */
        val expectedJobs: Set<String>,
        /** A2 totals by UTC day, today's included; a day without a digest in this JVM is absent. */
        val a2ByDay: Map<LocalDate, Long>,
        val today: LocalDate,
    )

    companion object {
        const val TEMPLATE = "email/adminReverifyDigest"
        const val SUBJECT = "Provjere kod partnera - dnevni pregled (Boat4You)"

        /** Every mailed digest carries a reason, so every subject gets the prefix. */
        const val SUBJECT_PREFIX = "⚠️ "

        /** First line of a mailed digest; the reasons follow, one per line. */
        const val REASONS_HEADING = "ZAŠTO OVAJ MAIL"

        /**
         * A2 criterion bar: below it on [A2_DAYS_BELOW] consecutive days, SEARCH_DEMOTE_STALE_OPTIONS goes to false (the
         * digest mails on that day), and a climb from below to this value or above mails as well.
         */
        const val A2_THRESHOLD = 1000L

        /** Consecutive daily digests below [A2_THRESHOLD]; the mail goes on exactly this day (the day of the flip). */
        const val A2_DAYS_BELOW = 3

        /** Days of A2 totals kept in memory (the streak needs [A2_DAYS_BELOW], the climb needs yesterday). */
        private const val A2_HISTORY_DAYS = 7L

        /**
         * Anomaly share in percent: a WRITE run that hid MORE than this share of the terms it quoted
         * (`hidden_periods` vs `quoted`), or a LOG run whose would-flip rows are MORE than this share of its calls.
         */
        const val ANOMALY_SHARE_PERCENT = 10L

        /**
         * Anomaly row bar: MORE than this many rows flipped to UNAVAILABLE in the last 24 h in total (flip log, admin
         * undos not counted), or MORE than this many would-flip rows in one LOG run.
         */
        const val ANOMALY_ROWS = 500L

        /**
         * The [ANOMALY_SHARE_PERCENT] rule needs at least this many quoted terms (WRITE) / calls (LOG): a run of a few
         * calls (a NauSys retry drain, an admin undo, D3 that counts no quotes) is judged by [ANOMALY_ROWS] alone.
         */
        const val MIN_SAMPLE_FOR_SHARE = 100L

        /** An agency escalated on at least this many days of the 7-day window is persistent and needs a decision. */
        const val PERSISTENT_ESCALATION_DAYS = 7L

        /** `NauSysD3Run.finish`: "D3 <mode> <label>: <reason>=<rows> ... rows, <n> agencies" - the routine run summary. */
        private val D3_SUMMARY = Regex("^D3 (LOG|WRITE) [^:]+: ")

        private val HR = Locale.forLanguageTag("hr")

        private const val RUNS_SQL =
            "SELECT id, job, mode, started_at, ended_at, calls, quoted, empty, unknown, upserts, first_strikes, hidden_periods, " +
                "hidden_rows, would_flip_rows, aborted_reason, escalated_agency_ids FROM reverify_run " +
                "WHERE started_at > now() - INTERVAL '24 hours' ORDER BY started_at, id"

        /** Agencies (ids) printed under one flip line, most rows first; the rest is only counted. */
        const val FLIP_AGENCIES_SHOWN = 10

        /** One flip line of [FLIPS_SQL]: `x` is a flip-log row of the group `g`. */
        private const val SAME_FLIP_GROUP =
            "x.run_id IS NOT DISTINCT FROM g.run_id AND x.job = g.job AND x.reason = g.reason AND x.external_system_id = g.external_system_id"

        /**
         * The flip-log rows of the last 24 h per run, job, reason and partner. `job` is what the writer stored with the
         * flip (MMK: the step's job, MMK_FREE step 2 / MMK_OPTION steps 2c-2b), so a flip is always listed under the
         * job that made it; the old statuses and the agency ids say what each line took off the site (20.10.2026: the
         * Y Knot OPTION rows were in run #38 MMK_OPTION, but only an agency query outside the digest could tell).
         */
        private const val FLIPS_SQL =
            "WITH f AS (SELECT l.run_id, l.job, l.reason, l.external_system_id, l.yacht_id, l.date_from, l.date_to, l.old_status, " +
                "y.agency_id FROM partner_offer_flip_log l LEFT JOIN yacht y ON y.id = l.yacht_id " +
                "WHERE l.created_at > now() - INTERVAL '24 hours') " +
                "SELECT g.run_id, g.job, g.reason, g.external_system_id, count(*) AS rows, " +
                "count(DISTINCT (g.yacht_id, g.date_from, g.date_to)) AS periods, count(DISTINCT g.agency_id) AS agencies, " +
                "(SELECT string_agg(s.old_status || ' ' || s.n, ', ' ORDER BY s.n DESC, s.old_status) " +
                "FROM (SELECT x.old_status, count(*) AS n FROM f x WHERE $SAME_FLIP_GROUP GROUP BY x.old_status) s) AS old_statuses, " +
                "(SELECT string_agg(a.agency_id || ': ' || a.n, ', ' ORDER BY a.n DESC, a.agency_id) " +
                "FROM (SELECT x.agency_id, count(*) AS n FROM f x WHERE $SAME_FLIP_GROUP AND x.agency_id IS NOT NULL " +
                "GROUP BY x.agency_id ORDER BY n DESC, x.agency_id LIMIT $FLIP_AGENCIES_SHOWN) a) AS top_agencies " +
                "FROM f g GROUP BY g.run_id, g.job, g.reason, g.external_system_id ORDER BY g.run_id, g.job, g.reason"

        private const val ESCALATIONS_SQL =
            "SELECT e.agency_id, string_agg(DISTINCT r.job, ', ') AS jobs, max(r.started_at)::date AS last_seen, " +
                "count(DISTINCT r.started_at::date) AS days, " +
                "bool_or(r.started_at > now() - INTERVAL '24 hours') AS in_last_24h, " +
                "bool_or(r.started_at <= now() - INTERVAL '24 hours') AS in_previous_days " +
                "FROM reverify_run r CROSS JOIN LATERAL unnest(r.escalated_agency_ids) AS e(agency_id) " +
                "WHERE r.started_at > now() - INTERVAL '7 days' GROUP BY e.agency_id ORDER BY last_seen DESC, e.agency_id"

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

        /**
         * Why the digest needs a human, one line each (empty = log only):
         *  1. a run of the last 24 h stopped (breaker, deadline, time budget, switch, rate limits - any note but the
         *     routine summary, see [isRoutineNote]) or never finished (still running at 07:00 or killed), or a job the
         *     switches run every day has no run in the last 24 h;
         *  2. an agency escalated in the last 24 h and not in the 6 days before (new), or escalated on
         *     [PERSISTENT_ESCALATION_DAYS] days of the last 7 (persistent);
         *  3. A2 below [A2_THRESHOLD] on the third consecutive daily digest ([A2_DAYS_BELOW]), or back from below to above it;
         *  4. a WRITE run hid more than [ANOMALY_SHARE_PERCENT] % of the terms it quoted, or the flips of the last 24 h
         *     exceed [ANOMALY_ROWS] rows;
         *  5. a LOG run would flip more than [ANOMALY_ROWS] rows or more than [ANOMALY_SHARE_PERCENT] % of its calls.
         * Pure: no clock, no database.
         */
        fun digestTriggers(facts: DigestFacts): List<String> =
            interruptions(facts) +
                escalations(facts.escalations) +
                a2Crossings(facts.a2ByDay, facts.today) +
                hideAnomalies(facts) +
                logRunAnomalies(facts.runs)

        /** The jobs that run every day under [snapshot]: the MMK free-offer reverify always, the others when switched on. */
        fun expectedDailyJobs(snapshot: RealPriceSwitches.Snapshot): Set<String> =
            buildSet {
                add(ReverifyJobs.MMK_FREE)
                if (snapshot.mmkOptionVerify != MmkOptionVerifyMode.OFF) add(ReverifyJobs.MMK_OPTION)
                if (snapshot.nausysReverify != NauSysReverifyMode.OFF) add(ReverifyJobs.NAUSYS_REVERIFY)
                if (snapshot.nausysD3Mode != NauSysD3Mode.LEGACY) add(ReverifyJobs.NAUSYS_D3)
            }

        /**
         * A note that is not an interruption: the D3 per-reason summary every D3 run closes with, and the summary of an
         * admin undo. Everything else in `aborted_reason` is a breaker or a stop.
         */
        fun isRoutineNote(
            job: String,
            note: String,
        ): Boolean = job == ReverifyJobs.UNDO || (job == ReverifyJobs.NAUSYS_D3 && D3_SUMMARY.containsMatchIn(note))

        /** [body] headed by [REASONS_HEADING] and the [triggers] when there are any, else [body] unchanged. */
        fun withReasons(
            body: String,
            triggers: List<String>,
        ): String =
            if (triggers.isEmpty()) {
                body
            } else {
                buildString {
                    appendLine(REASONS_HEADING)
                    triggers.forEach { appendLine("  $it") }
                    appendLine()
                    append(body)
                }
            }

        private fun interruptions(facts: DigestFacts): List<String> {
            val stopped =
                facts.runs.mapNotNull { r ->
                    when {
                        !r.ended -> "PREKID: run #${r.id} ${r.job} ${r.mode} nije završio (još traje ili je prekinut)"
                        r.note != null && !isRoutineNote(r.job, r.note) -> "PREKID: run #${r.id} ${r.job} ${r.mode}: ${r.note}"
                        else -> null
                    }
                }
            val missing = (facts.expectedJobs - facts.runs.map { it.job }.toSet()).sorted()
            return stopped + missing.map { "NEMA RUNA: $it se nije pokrenuo u zadnja 24 h (prekidači ga pokreću svaki dan)" }
        }

        private fun escalations(rows: List<EscalationFact>): List<String> =
            rows.sortedBy { it.agencyId }.flatMap { e ->
                listOfNotNull(
                    "NOVA ESKALACIJA: agencija ${e.agencyId} (prethodnih 6 dana nije bila eskalirana)".takeIf { e.inLast24h && !e.inPreviousDays },
                    "TRAJNA ESKALACIJA: agencija ${e.agencyId}, ${e.days} dana u zadnjih 7 - treba odluka"
                        .takeIf { e.days >= PERSISTENT_ESCALATION_DAYS },
                )
            }

        private fun a2Crossings(
            byDay: Map<LocalDate, Long>,
            today: LocalDate,
        ): List<String> {
            val now = byDay[today] ?: return emptyList()
            val daysBelow = generateSequence(today) { it.minusDays(1) }.takeWhile { d -> byDay[d]?.let { it < A2_THRESHOLD } == true }.count()
            val yesterday = byDay[today.minusDays(1)]
            return listOfNotNull(
                "A2: $now, ispod $A2_THRESHOLD $A2_DAYS_BELOW. dan zaredom - dan za SEARCH_DEMOTE_STALE_OPTIONS=false"
                    .takeIf { daysBelow == A2_DAYS_BELOW },
                "A2: ponovno iznad $A2_THRESHOLD (jučer $yesterday, danas $now)"
                    .takeIf { yesterday != null && yesterday < A2_THRESHOLD && now >= A2_THRESHOLD },
            )
        }

        private fun hideAnomalies(facts: DigestFacts): List<String> {
            val perRun =
                facts.runs
                    .filter { it.mode == ReverifyRunMode.WRITE.name && it.quoted >= MIN_SAMPLE_FOR_SHARE && exceedsShare(it.hiddenPeriods, it.quoted) }
                    .map {
                        "SKRIVANJE: run #${it.id} ${it.job} WRITE sakrio ${it.hiddenPeriods} termina = ${percent(it.hiddenPeriods, it.quoted)} % " +
                            "od ${it.quoted} kvotiranih (prag $ANOMALY_SHARE_PERCENT %)"
                    }
            val rows = facts.flips.filter { it.job != ReverifyJobs.UNDO }.sumOf { it.rows }
            return perRun + listOfNotNull("SKRIVANJE: $rows redaka -> UNAVAILABLE u zadnja 24 h (prag $ANOMALY_ROWS)".takeIf { rows > ANOMALY_ROWS })
        }

        private fun logRunAnomalies(runs: List<RunFact>): List<String> =
            runs
                .filter { it.mode == ReverifyRunMode.LOG.name }
                .filter { it.wouldFlipRows > ANOMALY_ROWS || (it.calls >= MIN_SAMPLE_FOR_SHARE && exceedsShare(it.wouldFlipRows, it.calls)) }
                .map {
                    "LOG RUN: run #${it.id} ${it.job} LOG wouldFlip=${it.wouldFlipRows} uz ${it.calls} poziva " +
                        "(prag: više od $ANOMALY_ROWS redaka ili $ANOMALY_SHARE_PERCENT % poziva)"
                }

        private fun exceedsShare(
            part: Long,
            whole: Long,
        ): Boolean = part * 100 > whole * ANOMALY_SHARE_PERCENT

        private fun percent(
            part: Long,
            whole: Long,
        ): String = String.format(HR, "%.1f", part * 100.0 / whole)
    }
}
