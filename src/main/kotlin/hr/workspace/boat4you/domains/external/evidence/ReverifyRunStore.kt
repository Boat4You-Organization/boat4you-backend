package hr.workspace.boat4you.domains.external.evidence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.LocalDateTime

/** `reverify_run.mode`: LOG = probes and bookkeeping only, flips counted; WRITE = flips written. */
enum class ReverifyRunMode { LOG, WRITE }

/** `reverify_run.job` names in use (VARCHAR(40); a new job may use its own name). */
object ReverifyJobs {
    const val MMK_FREE = "MMK_FREE"
    const val MMK_OPTION = "MMK_OPTION"
    const val NAUSYS_REVERIFY = "NAUSYS_REVERIFY"
    const val NAUSYS_D3 = "NAUSYS_D3"
}

/** Counter deltas of one run; every store call ADDS them to the stored values. */
data class ReverifyCounters(
    val calls: Int = 0,
    val quoted: Int = 0,
    val empty: Int = 0,
    val unknown: Int = 0,
    val upserts: Int = 0,
    val firstStrikes: Int = 0,
    val hiddenPeriods: Int = 0,
    val hiddenRows: Int = 0,
    val wouldFlipRows: Int = 0,
) {
    operator fun plus(o: ReverifyCounters) =
        ReverifyCounters(
            calls + o.calls,
            quoted + o.quoted,
            empty + o.empty,
            unknown + o.unknown,
            upserts + o.upserts,
            firstStrikes + o.firstStrikes,
            hiddenPeriods + o.hiddenPeriods,
            hiddenRows + o.hiddenRows,
            wouldFlipRows + o.wouldFlipRows,
        )

    internal fun args(): Array<Any> = arrayOf(calls, quoted, empty, unknown, upserts, firstStrikes, hiddenPeriods, hiddenRows, wouldFlipRows)
}

/** A `reverify_run` row. */
data class ReverifyRun(
    val id: Long,
    val job: String,
    val mode: ReverifyRunMode,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val counters: ReverifyCounters,
    val abortedReason: String?,
    val escalatedAgencyIds: List<Long>?,
)

/**
 * Run log of the reverify / D3 jobs (`reverify_run`, V9_82; DESIGN.md WS-B "Run log"). [start] opens a row, the job
 * adds counters as it goes ([addCounters], so a crash still leaves the partial numbers) and [finish] closes it.
 * Plain JDBC, one statement per call (joins the caller's transaction when there is one). Timestamps are DB time.
 */
@Component
class ReverifyRunStore(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** Opens a run and returns its id. */
    fun start(
        job: String,
        mode: ReverifyRunMode,
    ): Long {
        require(job.isNotBlank() && job.length <= JOB_MAX) { "job must be 1..$JOB_MAX characters: '$job'" }
        return jdbcTemplate.queryForObject(
            "INSERT INTO reverify_run (job, mode, started_at) VALUES (?, ?, now()) RETURNING id",
            Long::class.java,
            job,
            mode.name,
        )!!
    }

    /** Adds [delta] to the run's counters. */
    fun addCounters(
        runId: Long,
        delta: ReverifyCounters,
    ) {
        jdbcTemplate.update("UPDATE reverify_run SET $ADD_COUNTERS WHERE id = ?", *delta.args(), runId)
    }

    /**
     * Closes the run: adds [delta], sets `ended_at = now()`, the abort reason (cut to 200 characters) when given, and
     * the escalated agency ids (ids only, never names) when given; null leaves the stored value.
     */
    fun finish(
        runId: Long,
        delta: ReverifyCounters = ReverifyCounters(),
        abortedReason: String? = null,
        escalatedAgencyIds: Collection<Long>? = null,
    ) {
        jdbcTemplate.update { con ->
            con
                .prepareStatement(
                    "UPDATE reverify_run SET $ADD_COUNTERS, ended_at = now(), " +
                        "aborted_reason = COALESCE(?, aborted_reason), escalated_agency_ids = COALESCE(?, escalated_agency_ids) " +
                        "WHERE id = ?",
                ).apply {
                    val counters = delta.args()
                    counters.forEachIndexed { i, v -> setInt(i + 1, v as Int) }
                    var i = counters.size + 1
                    setString(i++, abortedReason?.take(ABORT_REASON_MAX))
                    if (escalatedAgencyIds == null) {
                        setNull(i++, java.sql.Types.ARRAY)
                    } else {
                        setArray(i++, con.createArrayOf("bigint", escalatedAgencyIds.toTypedArray()))
                    }
                    setLong(i, runId)
                }
        }
    }

    /** The run row, or null. */
    fun find(runId: Long): ReverifyRun? = jdbcTemplate.query("SELECT * FROM reverify_run WHERE id = ?", ROW_MAPPER, runId).firstOrNull()

    companion object {
        private const val JOB_MAX = 40
        private const val ABORT_REASON_MAX = 200

        private const val ADD_COUNTERS =
            "calls = calls + ?, quoted = quoted + ?, empty = empty + ?, unknown = unknown + ?, upserts = upserts + ?, " +
                "first_strikes = first_strikes + ?, hidden_periods = hidden_periods + ?, hidden_rows = hidden_rows + ?, " +
                "would_flip_rows = would_flip_rows + ?"

        private val ROW_MAPPER =
            RowMapper { rs, _ ->
                ReverifyRun(
                    id = rs.getLong("id"),
                    job = rs.getString("job"),
                    mode = ReverifyRunMode.valueOf(rs.getString("mode")),
                    startedAt = rs.getTimestamp("started_at").toLocalDateTime(),
                    endedAt = rs.getTimestamp("ended_at")?.let(Timestamp::toLocalDateTime),
                    counters =
                        ReverifyCounters(
                            calls = rs.getInt("calls"),
                            quoted = rs.getInt("quoted"),
                            empty = rs.getInt("empty"),
                            unknown = rs.getInt("unknown"),
                            upserts = rs.getInt("upserts"),
                            firstStrikes = rs.getInt("first_strikes"),
                            hiddenPeriods = rs.getInt("hidden_periods"),
                            hiddenRows = rs.getInt("hidden_rows"),
                            wouldFlipRows = rs.getInt("would_flip_rows"),
                        ),
                    abortedReason = rs.getString("aborted_reason"),
                    escalatedAgencyIds =
                        rs.getArray("escalated_agency_ids")?.let { arr ->
                            (arr.array as Array<*>).map { (it as Number).toLong() }
                        },
                )
            }
    }
}
