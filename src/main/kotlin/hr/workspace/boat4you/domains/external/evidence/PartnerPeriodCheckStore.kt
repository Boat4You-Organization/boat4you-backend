package hr.workspace.boat4you.domains.external.evidence

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate

/**
 * What a partner answered for exactly one (yacht, period), as stored in `partner_period_check.last_result`.
 *
 * [decided] = a real answer (bookkeeping moves `last_checked_on`); [quoted] = the partner returned a row for exactly
 * these dates (moves `last_quoted_on`). UNKNOWN (error, non-OK body, 429, timeout) is never evidence: it only makes
 * sure the row exists and keeps the last decided answer.
 */
enum class PeriodCheckResult(
    val decided: Boolean,
    val quoted: Boolean,
) {
    QUOTED_FREE(true, true),
    QUOTED_OPTION(true, true),
    QUOTED_OTHER(true, true),
    EMPTY(true, false),
    UNPRICED(true, false),
    ROUTE_GONE(true, false),

    /** NauSys: an empty 7-night answer inside a season whose minimal duration is longer (excluded from breakers). */
    MINSTAY(true, false),
    UNKNOWN(false, false),
}

/** `partner_period_check.priority` values (lower = more urgent; the stored value only ever moves down). */
object PeriodCheckPriority {
    const val SYNTHETIC = 1
    const val STALE_OPTION = 2
    const val ON_DEMAND_OR_BOOKING = 3
    const val OFF_GRID_OR_STALE_UNAVAILABLE = 4
    const val SAMPLE = 5
}

/** One exact period of one yacht. */
data class PeriodKey(
    val yachtId: Long,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
)

/** One probe answer, for the batch variants. */
data class PeriodCheck(
    val yachtId: Long,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val priority: Int,
    val result: PeriodCheckResult,
    val quoted: Boolean = result.quoted,
)

/** A `partner_period_check` row. */
data class PartnerPeriodCheckRow(
    val externalSystem: ExternalSystemEnum,
    val yachtId: Long,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val priority: Int,
    val lastCheckedOn: LocalDate?,
    val lastQuotedOn: LocalDate?,
    val lastResult: PeriodCheckResult?,
    val firstEmptyOn: LocalDate?,
    val hiddenOn: LocalDate?,
    val hiddenRows: Int,
)

/**
 * Probe bookkeeping and NauSys strikes for both partners (`partner_period_check`, V9_82; DESIGN.md section 1.1).
 *
 * One row per (partner, yacht, exact period). Plain JDBC: every method is one `INSERT ... ON CONFLICT DO UPDATE` /
 * `UPDATE` (or a JDBC batch of them) that joins the caller's transaction when there is one and otherwise commits on
 * its own. `today` defaults to the JVM date (the servers run in UTC); jobs pass their own run date.
 *
 * Column meanings: `priority` only ever moves down (LEAST) except through [clearStrike] with an explicit priority;
 * `last_checked_on` / `last_result` = the last decided answer; `last_quoted_on` = the last day the partner returned a
 * row for these dates; `first_empty_on` = an open NauSys strike (MMK strikes stay in `mmk_free_week_strike`);
 * `hidden_on` / `hidden_rows` = the day we hid exactly these dates and how many offer rows (the search treats this as
 * evidence that the period is taken).
 */
@Suppress("TooManyFunctions") // single + batch variant per operation is the shared API of WS-B/C
@Component
class PartnerPeriodCheckStore(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Records one probe answer: `last_checked_on = today`, `last_result`, and `last_quoted_on = today` when [quoted];
     * `priority = LEAST(existing, priority)`. An UNKNOWN answer only creates the row (pending) and lowers the priority.
     */
    @Suppress("LongParameterList") // the exact period key (partner, yacht, from, to) is 4 of them
    fun recordChecked(
        sys: ExternalSystemEnum,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        priority: Int,
        result: PeriodCheckResult,
        quoted: Boolean = result.quoted,
        today: LocalDate = LocalDate.now(),
    ) {
        jdbcTemplate.update(RECORD_CHECKED_SQL, *checkedArgs(sys, PeriodCheck(yachtId, dateFrom, dateTo, priority, result, quoted), today))
    }

    /** [recordChecked] for many periods in one JDBC batch. */
    fun recordCheckedBatch(
        sys: ExternalSystemEnum,
        checks: List<PeriodCheck>,
        today: LocalDate = LocalDate.now(),
    ) {
        if (checks.isEmpty()) return
        jdbcTemplate.batchUpdate(RECORD_CHECKED_SQL, checks.map { checkedArgs(sys, it, today) }, CHECKED_TYPES)
    }

    /**
     * Records a negative answer (EMPTY / UNPRICED / ROUTE_GONE / MINSTAY) as a strike: `first_empty_on = today` only
     * when no strike is open (an open strike is never moved), plus the [recordChecked] bookkeeping. A row that carries
     * a hide record (`hidden_on`) belongs to an earlier cycle: the period is shown again, so the hide record is closed
     * and this answer is a first strike.
     *
     * @return the row's `first_empty_on` after the write: `today` for a first strike, an earlier day when this answer
     *   is the second strike (the caller then decides on a hide).
     */
    @Suppress("LongParameterList") // the exact period key (partner, yacht, from, to) is 4 of them
    fun recordFirstStrike(
        sys: ExternalSystemEnum,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        priority: Int,
        result: PeriodCheckResult,
        today: LocalDate = LocalDate.now(),
    ): LocalDate {
        requireStrikeResult(result)
        return jdbcTemplate.queryForObject(
            "$RECORD_STRIKE_SQL RETURNING first_empty_on",
            { rs, _ -> rs.getObject(1, LocalDate::class.java) },
            *strikeArgs(sys, PeriodCheck(yachtId, dateFrom, dateTo, priority, result), today),
        )!!
    }

    /** [recordFirstStrike] for many periods in one JDBC batch (no return value: read back with [find]). */
    fun recordFirstStrikeBatch(
        sys: ExternalSystemEnum,
        checks: List<PeriodCheck>,
        today: LocalDate = LocalDate.now(),
    ) {
        if (checks.isEmpty()) return
        checks.forEach { requireStrikeResult(it.result) }
        jdbcTemplate.batchUpdate(RECORD_STRIKE_SQL, checks.map { strikeArgs(sys, it, today) }, STRIKE_TYPES)
    }

    /**
     * The partner quotes the period again: closes the strike and the hide record (`first_empty_on`, `hidden_on` =
     * NULL, `hidden_rows` = 0), like the MMK strike row that is deleted on a quote. With [priority] the stored
     * priority is reset to that value (e.g. back to [PeriodCheckPriority.SAMPLE] after a quote).
     *
     * @return 1 when the row existed, else 0.
     */
    fun clearStrike(
        sys: ExternalSystemEnum,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        priority: Int? = null,
    ): Int = jdbcTemplate.update(CLEAR_STRIKE_SQL, *clearArgs(sys, PeriodKey(yachtId, dateFrom, dateTo), priority))

    /** [clearStrike] for many periods in one JDBC batch. */
    fun clearStrikeBatch(
        sys: ExternalSystemEnum,
        periods: List<PeriodKey>,
        priority: Int? = null,
    ) {
        if (periods.isEmpty()) return
        jdbcTemplate.batchUpdate(CLEAR_STRIKE_SQL, periods.map { clearArgs(sys, it, priority) }, CLEAR_TYPES)
    }

    /**
     * Positive partner evidence outside the reverifier (the nightly / near-term grid, a warm): the partner quoted
     * exactly these periods again, so an open strike and a stale hide record are closed. Only rows that carry one are
     * touched (no write for the common case), the priority stays.
     */
    fun closeStrikesOnQuoteBatch(
        sys: ExternalSystemEnum,
        periods: List<PeriodKey>,
    ) {
        if (periods.isEmpty()) return
        jdbcTemplate.batchUpdate(
            CLOSE_ON_QUOTE_SQL,
            periods.distinct().map { arrayOf<Any>(sys.value, it.yachtId, it.dateFrom, it.dateTo) },
            intArrayOf(Types.SMALLINT, Types.BIGINT, Types.DATE, Types.DATE),
        )
    }

    /** Records that exactly these dates were hidden today ([hiddenRows] offer rows flipped). */
    @Suppress("LongParameterList") // the exact period key (partner, yacht, from, to) is 4 of them
    fun markHidden(
        sys: ExternalSystemEnum,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        hiddenRows: Int,
        today: LocalDate = LocalDate.now(),
    ) {
        jdbcTemplate.update(
            "INSERT INTO partner_period_check (external_system_id, yacht_id, date_from, date_to, hidden_on, hidden_rows) " +
                "VALUES (?, ?, ?, ?, ?, ?) $ON_CONFLICT hidden_on = EXCLUDED.hidden_on, hidden_rows = EXCLUDED.hidden_rows",
            sys.value,
            yachtId,
            dateFrom,
            dateTo,
            today,
            hiddenRows,
        )
    }

    /**
     * Queues a period for a probe without an answer (WS-C booking guard, on-demand hooks): creates the row when
     * missing, otherwise only lowers the priority. Never touches the evidence columns.
     */
    fun upsertPending(
        sys: ExternalSystemEnum,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        priority: Int,
    ) {
        jdbcTemplate.update(UPSERT_PENDING_SQL, sys.value, yachtId, dateFrom, dateTo, priority)
    }

    /** [upsertPending] for many periods in one JDBC batch. */
    fun upsertPendingBatch(
        sys: ExternalSystemEnum,
        periods: List<PeriodKey>,
        priority: Int,
    ) {
        if (periods.isEmpty()) return
        jdbcTemplate.batchUpdate(
            UPSERT_PENDING_SQL,
            periods.map { arrayOf<Any>(sys.value, it.yachtId, it.dateFrom, it.dateTo, priority) },
            intArrayOf(Types.SMALLINT, Types.BIGINT, Types.DATE, Types.DATE, Types.SMALLINT),
        )
    }

    /** Open strikes (`first_empty_on` set, not hidden), oldest strike first, then most urgent. */
    fun findOpenStrikes(
        sys: ExternalSystemEnum,
        limit: Int,
    ): List<PartnerPeriodCheckRow> =
        jdbcTemplate.query(
            "SELECT * FROM partner_period_check WHERE external_system_id = ? AND first_empty_on IS NOT NULL " +
                "AND hidden_on IS NULL ORDER BY first_empty_on, priority, yacht_id, date_from LIMIT ?",
            ROW_MAPPER,
            sys.value,
            limit,
        )

    /** The row of exactly this period, or null. */
    fun find(
        sys: ExternalSystemEnum,
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): PartnerPeriodCheckRow? =
        jdbcTemplate
            .query(
                "SELECT * FROM partner_period_check WHERE external_system_id = ? AND yacht_id = ? AND date_from = ? AND date_to = ?",
                ROW_MAPPER,
                sys.value,
                yachtId,
                dateFrom,
                dateTo,
            ).firstOrNull()

    private fun requireStrikeResult(result: PeriodCheckResult) = require(result.decided && !result.quoted) { "A strike needs a negative decided answer, got $result" }

    private fun checkedArgs(
        sys: ExternalSystemEnum,
        c: PeriodCheck,
        today: LocalDate,
    ): Array<Any?> =
        arrayOf(
            sys.value,
            c.yachtId,
            c.dateFrom,
            c.dateTo,
            c.priority,
            if (c.result.decided) today else null,
            if (c.result.decided && c.quoted) today else null,
            if (c.result.decided) c.result.name else null,
        )

    private fun strikeArgs(
        sys: ExternalSystemEnum,
        c: PeriodCheck,
        today: LocalDate,
    ): Array<Any?> = arrayOf(sys.value, c.yachtId, c.dateFrom, c.dateTo, c.priority, today, c.result.name, today)

    private fun clearArgs(
        sys: ExternalSystemEnum,
        p: PeriodKey,
        priority: Int?,
    ): Array<Any?> = arrayOf(priority, sys.value, p.yachtId, p.dateFrom, p.dateTo)

    companion object {
        private const val ON_CONFLICT = "ON CONFLICT (external_system_id, yacht_id, date_from, date_to) DO UPDATE SET"
        private const val LEAST_PRIORITY = "priority = LEAST(partner_period_check.priority, EXCLUDED.priority)"

        private const val RECORD_CHECKED_SQL =
            "INSERT INTO partner_period_check (external_system_id, yacht_id, date_from, date_to, priority, " +
                "last_checked_on, last_quoted_on, last_result) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "$ON_CONFLICT $LEAST_PRIORITY, " +
                "last_checked_on = COALESCE(EXCLUDED.last_checked_on, partner_period_check.last_checked_on), " +
                "last_quoted_on = COALESCE(EXCLUDED.last_quoted_on, partner_period_check.last_quoted_on), " +
                "last_result = COALESCE(EXCLUDED.last_result, partner_period_check.last_result)"

        private const val RECORD_STRIKE_SQL =
            "INSERT INTO partner_period_check (external_system_id, yacht_id, date_from, date_to, priority, " +
                "last_checked_on, last_result, first_empty_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "$ON_CONFLICT $LEAST_PRIORITY, last_checked_on = EXCLUDED.last_checked_on, " +
                "last_result = EXCLUDED.last_result, " +
                // A row with hidden_on set belongs to an earlier hide: the period is shown again (a later partner quote
                // restored it), so this answer starts a new two-day cycle, like the MMK strike table does.
                "first_empty_on = CASE WHEN partner_period_check.hidden_on IS NOT NULL THEN EXCLUDED.first_empty_on " +
                "ELSE COALESCE(partner_period_check.first_empty_on, EXCLUDED.first_empty_on) END, " +
                "hidden_on = NULL, hidden_rows = 0"

        private const val CLEAR_STRIKE_SQL =
            "UPDATE partner_period_check SET first_empty_on = NULL, hidden_on = NULL, hidden_rows = 0, " +
                "priority = COALESCE(CAST(? AS SMALLINT), priority) " +
                "WHERE external_system_id = ? AND yacht_id = ? AND date_from = ? AND date_to = ?"

        private const val CLOSE_ON_QUOTE_SQL =
            "UPDATE partner_period_check SET first_empty_on = NULL, hidden_on = NULL, hidden_rows = 0 " +
                "WHERE external_system_id = ? AND yacht_id = ? AND date_from = ? AND date_to = ? " +
                "AND (first_empty_on IS NOT NULL OR hidden_on IS NOT NULL)"

        private const val UPSERT_PENDING_SQL =
            "INSERT INTO partner_period_check (external_system_id, yacht_id, date_from, date_to, priority) " +
                "VALUES (?, ?, ?, ?, ?) $ON_CONFLICT $LEAST_PRIORITY"

        private val CHECKED_TYPES =
            intArrayOf(Types.SMALLINT, Types.BIGINT, Types.DATE, Types.DATE, Types.SMALLINT, Types.DATE, Types.DATE, Types.VARCHAR)
        private val STRIKE_TYPES =
            intArrayOf(Types.SMALLINT, Types.BIGINT, Types.DATE, Types.DATE, Types.SMALLINT, Types.DATE, Types.VARCHAR, Types.DATE)
        private val CLEAR_TYPES = intArrayOf(Types.SMALLINT, Types.SMALLINT, Types.BIGINT, Types.DATE, Types.DATE)

        private fun ResultSet.date(column: String): LocalDate? = getObject(column, Date::class.java)?.toLocalDate()

        private val ROW_MAPPER =
            RowMapper { rs, _ ->
                PartnerPeriodCheckRow(
                    externalSystem = ExternalSystemEnum.fromValue(rs.getInt("external_system_id")),
                    yachtId = rs.getLong("yacht_id"),
                    dateFrom = rs.date("date_from")!!,
                    dateTo = rs.date("date_to")!!,
                    priority = rs.getInt("priority"),
                    lastCheckedOn = rs.date("last_checked_on"),
                    lastQuotedOn = rs.date("last_quoted_on"),
                    lastResult = rs.getString("last_result")?.let { r -> PeriodCheckResult.entries.firstOrNull { it.name == r } },
                    firstEmptyOn = rs.date("first_empty_on"),
                    hiddenOn = rs.date("hidden_on"),
                    hiddenRows = rs.getInt("hidden_rows"),
                )
            }
    }
}
