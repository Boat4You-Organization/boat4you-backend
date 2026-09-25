package hr.workspace.boat4you.domains.catalouge.charterfacts

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.BaseCount
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.BoatStats
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.Key
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.ModelCount
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.MonthStats
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/**
 * Recomputes every `charter_facts` row (landing-page inventory facts per destination and boat type).
 *
 * Runs ONLY on the scheduler node (data-sync profile, cusma3): cusma2 is the only API node and has an OOM history,
 * so nothing here may run per request. The whole run is set-based — a handful of statements over the whole
 * promoted-country population, grouped by did / vessel type with GROUPING SETS — never a per-destination loop.
 *
 * Population (mirrors the search, R__1_03 + YachtQueryingService):
 *  - EXTERNAL yachts, sys_active, agency active and not availability_blocked (dual-source agencies are synced from
 *    their one primary source, so each boat exists once);
 *  - weekly offers = exactly 7 nights, date_from in [today, today + 12 months), pickup marina in a promoted country;
 *  - one row per yacht-week (a week can have a BAREBOAT and a CREWED row, or a one-way variant): the round-trip,
 *    bookable (not UNAVAILABLE), cheapest row names the base, the price is the cheapest non-UNAVAILABLE client_price (EUR, what the listing shows), and the
 *    week counts as available when any of its rows is FREE, and bookable when any of its rows is not UNAVAILABLE;
 *  - a boat belongs to a did only when it has at least one BOOKABLE week based there — search hides UNAVAILABLE rows
 *    (YachtQueryingService, offerStatus <> UNAVAILABLE for every public caller), so a boat whose every week is
 *    UNAVAILABLE (withdrawn by the MMK reverifier, blocked by owner weeks) is not counted: activeBoats, the per-boat
 *    figures, models and bases all use members only. Month / check-in figures use all weeks of MEMBER boats (their
 *    UNAVAILABLE weeks stay in the availability denominators);
 *  - did membership by pickup marina: c- = marina country (country.code2), r- = location_region with the search's
 *    own-country guard, l- = the marina and its same-name siblings in the same country (findMarinaIdsByFoldedName).
 *    Drop-off-only matches (one-way INTO the destination) are not counted — facts describe boats based there.
 *
 * Keys written: every promoted country (c-) with at least one boat, every r- / l- with >= [MIN_BOATS] boats, each
 * for all types plus every vessel type with >= [MIN_BOATS] boats in that did.
 *
 * Everything happens in ONE transaction on one connection (temp tables ON COMMIT DROP, SET LOCAL limits), and the
 * table is replaced at the end of it: readers see the old snapshot until COMMIT, never a half-written one. A run
 * producing less than half of the rows already stored is treated as broken input (e.g. an empty offer table after
 * an incident) and rolled back, keeping yesterday's facts.
 */
@Profile("data-sync")
@Service
class CharterFactsComputeService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${charter-facts.countries:BS,ES,FR,GD,GR,HR,IT,ME,MQ,SC,TR,VG}")
    countriesCsv: String,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    /** The 12 promoted countries (frontend promoted-countries.config.ts). Validated, so safe to inline in SQL. */
    private val countries: List<String> =
        countriesCsv
            .split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .onEach { require(COUNTRY_CODE.matches(it)) { "charter-facts.countries: '$it' is not a 2-letter code" } }
            .distinct()

    data class Summary(
        val stored: Boolean,
        val rows: Int,
        val previousRows: Int,
        val weeks: Long,
        val boats: Long,
        val dids: Int,
        val millis: Long,
    )

    /** Newest stored computed_at (null = table empty) — for the job's staleness check. */
    fun latestComputedAt(): Instant? =
        jdbcTemplate.queryForObject("SELECT max(computed_at) FROM charter_facts", java.sql.Timestamp::class.java)?.toInstant()

    /**
     * [force] = skip the "fewer than half of the stored rows" guard (ops, for a legitimate large shrink). The
     * empty-result guard always applies: zero rows is never a legitimate outcome.
     */
    fun recompute(force: Boolean = false): Summary {
        val start = System.currentTimeMillis()
        val summary =
            jdbcTemplate.execute(
                ConnectionCallback<Summary> { conn ->
                    val autoCommit = conn.autoCommit
                    conn.autoCommit = false
                    try {
                        val result = computeAndReplace(JdbcTemplate(SingleConnectionDataSource(conn, true)), start, force)
                        if (result.stored) conn.commit() else conn.rollback()
                        result
                    } catch (e: Exception) {
                        conn.rollback()
                        throw e
                    } finally {
                        conn.autoCommit = autoCommit
                    }
                },
            )!!
        log.info(
            "Charter facts: {} rows ({} dids) from {} yacht-weeks / {} boats in {} ms (previous {} rows){}",
            summary.rows,
            summary.dids,
            summary.weeks,
            summary.boats,
            summary.millis,
            summary.previousRows,
            if (summary.stored) "" else " — NOT stored, previous facts kept",
        )
        return summary
    }

    private fun computeAndReplace(
        jdbc: JdbcTemplate,
        start: Long,
        force: Boolean,
    ): Summary {
        // SET LOCAL: reset automatically at COMMIT / ROLLBACK, so the pooled connection goes back clean.
        jdbc.execute("SET LOCAL statement_timeout = '${STATEMENT_TIMEOUT_SECONDS}s'")
        jdbc.execute("SET LOCAL lock_timeout = '5s'")
        jdbc.execute("SET LOCAL work_mem = '128MB'")
        jdbc.execute("SET LOCAL jit = off")

        val codes = countries.joinToString(",") { "'$it'" }
        jdbc.execute(weekSql(codes))
        jdbc.execute("ANALYZE cf_week")
        jdbc.execute(SCOPE_SQL)
        jdbc.execute(MEMBER_SQL)
        jdbc.execute(KEY_SQL.replace(":minBoats", MIN_BOATS.toString()))
        jdbc.execute("DELETE FROM cf_scope s WHERE NOT EXISTS (SELECT 1 FROM cf_key k WHERE k.did = s.did)")
        jdbc.execute("DELETE FROM cf_member m WHERE NOT EXISTS (SELECT 1 FROM cf_key k WHERE k.did = m.did)")
        jdbc.execute(boatSql())
        listOf("cf_scope", "cf_member", "cf_boat").forEach { jdbc.execute("ANALYZE $it") }

        val window = jdbc.queryForMap("SELECT CURRENT_DATE AS f, (CURRENT_DATE + INTERVAL '12 months')::date - 1 AS t")
        val windowFrom = (window["f"] as java.sql.Date).toLocalDate()
        val windowTo = (window["t"] as java.sql.Date).toLocalDate()
        val weeks = jdbc.queryForObject("SELECT count(*) FROM cf_week", Long::class.java) ?: 0L
        val boats = jdbc.queryForObject("SELECT count(DISTINCT yacht_id) FROM cf_member", Long::class.java) ?: 0L

        val keys = jdbc.query("SELECT did, vessel_type FROM cf_key") { rs, _ -> Key(rs.getString(1), rs.getString(2)) }
        val boatStats = jdbc.query(BOAT_STATS_SQL) { rs, _ -> key(rs) to boatStats(rs) }.toMap()
        val months = jdbc.query(MONTH_SQL) { rs, _ -> key(rs) to monthStats(rs) }.groupBy({ it.first }, { it.second })
        val checkIns =
            jdbc
                .query(CHECK_IN_SQL) { rs, _ -> Triple(key(rs), rs.getInt("dow"), rs.getLong("weeks")) }
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, v) -> v.toMap() }
        val models =
            jdbc
                .query(TOP_MODELS_SQL.replace(":top", CharterFactsMath.TOP_MODELS.toString())) { rs, _ ->
                    key(rs) to ModelCount(rs.getString("manufacturer"), rs.getString("model"), rs.getLong("n"))
                }.groupBy({ it.first }, { it.second })
        val bases =
            jdbc
                .query(TOP_BASES_SQL.replace(":top", CharterFactsMath.TOP_BASES.toString())) { rs, _ ->
                    key(rs) to BaseCount(rs.getLong("location_id"), rs.getString("name"), rs.getLong("n"))
                }.groupBy({ it.first }, { it.second })
        // Boat type mix for the all-types rows: every per-type count, also types below MIN_BOATS.
        val typeMix =
            boatStats.entries
                .filter { it.key.vesselType != null }
                .groupBy({ it.key.did }, { it.key.vesselType!! to it.value.boats })
                .mapValues { (_, v) -> v.toMap() }

        val payloads =
            keys.mapNotNull { k ->
                val stats = boatStats[k] ?: return@mapNotNull null
                k to
                    CharterFactsMath.buildPayload(
                        CharterFactsMath.Inputs(
                            boats = stats,
                            months = months[k].orEmpty(),
                            checkInDays = checkIns[k].orEmpty(),
                            topModels = models[k].orEmpty(),
                            topBases = bases[k].orEmpty(),
                            boatTypeMix = if (k.vesselType == null) typeMix[k.did].orEmpty() else null,
                            windowFrom = windowFrom,
                            windowTo = windowTo,
                        ),
                    )
            }

        val previous = jdbc.queryForObject("SELECT count(*) FROM charter_facts", Int::class.java) ?: 0
        val dids = payloads.map { it.first.did }.distinct().size
        val tooFew = payloads.isEmpty() || (!force && payloads.size * 2 < previous)
        if (tooFew) {
            log.error(
                "Charter facts: only {} rows computed vs {} stored — refusing to replace (empty/broken offer data?). " +
                    "If the shrink is legitimate: POST /admin/charter-facts/recompute?force=true on the scheduler node",
                payloads.size,
                previous,
            )
            return Summary(false, payloads.size, previous, weeks, boats, dids, System.currentTimeMillis() - start)
        }

        jdbc.update("DELETE FROM charter_facts")
        jdbc.batchUpdate(
            "INSERT INTO charter_facts (did, vessel_type, computed_at, payload) VALUES (?, ?, now(), CAST(? AS jsonb))",
            payloads.map { (k, p) -> arrayOf<Any?>(k.did, k.vesselType, objectMapper.writeValueAsString(p)) },
        )
        if (force && payloads.size * 2 < previous) {
            log.warn("Charter facts: forced replace of {} stored rows with {} rows", previous, payloads.size)
        }
        return Summary(true, payloads.size, previous, weeks, boats, dids, System.currentTimeMillis() - start)
    }

    private fun key(rs: ResultSet) = Key(rs.getString("did"), rs.getString("vessel_type"))

    private fun boatStats(rs: ResultSet) =
        BoatStats(
            boats = rs.getLong("boats"),
            buildYearMedian = rs.getObject("build_year_median")?.let { (it as Number).toInt() },
            buildYearN = rs.getLong("build_year_n"),
            depositMin = rs.getBigDecimal("deposit_min"),
            depositMedian = rs.getBigDecimal("deposit_median"),
            depositMax = rs.getBigDecimal("deposit_max"),
            depositN = rs.getLong("deposit_n"),
            skipperP25 = rs.getBigDecimal("skipper_p25"),
            skipperMedian = rs.getBigDecimal("skipper_median"),
            skipperP75 = rs.getBigDecimal("skipper_p75"),
            skipperN = rs.getLong("skipper_n"),
            obligatoryMedian = rs.getBigDecimal("obligatory_median"),
            obligatoryN = rs.getLong("obligatory_n"),
        )

    private fun monthStats(rs: ResultSet) =
        MonthStats(
            month = rs.getString("month"),
            weeks = rs.getLong("weeks"),
            freeWeeks = rs.getLong("free_weeks"),
            priced = rs.getLong("priced"),
            p25 = rs.getBigDecimal("p25"),
            median = rs.getBigDecimal("median"),
            p75 = rs.getBigDecimal("p75"),
        )

    private fun weekSql(codes: String) =
        """
        CREATE TEMP TABLE cf_week ON COMMIT DROP AS
        SELECT DISTINCT ON (o.yacht_id, o.date_from)
               o.yacht_id,
               o.date_from,
               o.location_from AS location_id,
               y.vessel_type,
               bool_or(o.status = 'FREE') OVER wk AS is_free,
               bool_or(o.status <> 'UNAVAILABLE') OVER wk AS has_bookable,
               min(o.client_price) FILTER (WHERE o.status <> 'UNAVAILABLE' AND o.client_price > 0) OVER wk AS price
        FROM offer o
        JOIN yacht y     ON y.id = o.yacht_id AND y.entry_type = 'EXTERNAL' AND y.sys_active
        JOIN agency a    ON a.id = y.agency_id AND a.active AND NOT a.availability_blocked
        JOIN location lf ON lf.id = o.location_from
        WHERE o.date_from >= CURRENT_DATE
          AND o.date_from < (CURRENT_DATE + INTERVAL '12 months')::date
          AND o.date_to = o.date_from + 7
          AND o.status NOT IN ('UNKNOWN', 'CANCELLED', 'INFO')
          AND lf.country_code IN ($codes)
        WINDOW wk AS (PARTITION BY o.yacht_id, o.date_from)
        ORDER BY o.yacht_id, o.date_from, (o.location_from = o.location_to) DESC, (o.status <> 'UNAVAILABLE') DESC,
                 o.client_price ASC NULLS LAST, o.id
        """.trimIndent()

    private fun boatSql(): String {
        val m = CharterFactsMath.weeklyMultiplierSql("ye.unit")
        val validInWindow =
            "(ye.valid_to IS NULL OR ye.valid_to >= CURRENT_DATE) " +
                "AND (ye.valid_from IS NULL OR ye.valid_from < (CURRENT_DATE + INTERVAL '12 months')::date)"
        val validToday =
            "((ye.valid_from IS NULL OR ye.valid_from <= CURRENT_DATE) AND (ye.valid_to IS NULL OR ye.valid_to >= CURRENT_DATE))"
        return """
            CREATE TEMP TABLE cf_boat ON COMMIT DROP AS
            WITH boats AS (SELECT DISTINCT yacht_id FROM cf_member),
            sk AS (
                -- The site's canonical Skipper extra (extras_id 1, R__1_04: "Skipper" anywhere in the name, e.g.
                -- "Professional skipper") or a name starting with "Skipper"; SKIPPER_EXCLUDE drops the non-skipper rows.
                -- One skipper figure per boat: a plain "Skipper" row wins over variants ("Skipper + food"), then the
                -- cheapest. Optional or obligatory; free (included) and non-weekly units are skipped; weekly amounts
                -- outside [$SKIPPER_MIN_WEEKLY, $SKIPPER_MAX_WEEKLY] EUR are partner unit mistakes (a per-day price
                -- filed "per booking") and are dropped.
                SELECT DISTINCT ON (ye.yacht_id) ye.yacht_id, ye.price * $m AS weekly
                FROM yacht_extras ye
                JOIN boats b ON b.yacht_id = ye.yacht_id
                WHERE (ye.extras_id = $SKIPPER_EXTRAS_ID OR ye.name ILIKE 'skipper%')
                  AND ye.name !~* '$SKIPPER_EXCLUDE'
                  AND ye.price > 0
                  AND ye.price * $m BETWEEN $SKIPPER_MIN_WEEKLY AND $SKIPPER_MAX_WEEKLY
                  AND $validInWindow
                ORDER BY ye.yacht_id, (lower(btrim(ye.name)) = 'skipper') DESC, ye.price * $m, ye.id
            ),
            ob_rows AS (
                -- One row per obligatory extra name (the one valid today, else the next season's), weekly-convertible
                -- units only, deposit-like items (refundable deposit, deposit/damage waivers, insurance) excluded.
                SELECT DISTINCT ON (ye.yacht_id, lower(btrim(ye.name))) ye.yacht_id, ye.price * $m AS weekly
                FROM yacht_extras ye
                JOIN boats b ON b.yacht_id = ye.yacht_id
                WHERE ye.obligatory
                  AND ye.name IS NOT NULL
                  AND ye.price >= 0
                  AND $m IS NOT NULL
                  AND ye.name !~* '$DEPOSIT_LIKE'
                  AND $validInWindow
                ORDER BY ye.yacht_id, lower(btrim(ye.name)), $validToday DESC, ye.valid_from ASC NULLS FIRST, ye.id
            ),
            ob AS (
                -- Per-boat fees only: per-person items (tourist tax, meal plans) need the party size and are not in
                -- the sum. Left out (NULL) instead of understated: a boat with no extras rows at all (unsynced), and
                -- a boat with an obligatory percentage item (APA on crewed yachts) - its real extras are unknowable.
                SELECT b.yacht_id, COALESCE(sum(r.weekly), 0) AS weekly
                FROM boats b
                LEFT JOIN ob_rows r ON r.yacht_id = b.yacht_id
                WHERE EXISTS (SELECT 1 FROM yacht_extras x WHERE x.yacht_id = b.yacht_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM yacht_extras ye
                      WHERE ye.yacht_id = b.yacht_id AND ye.obligatory AND ye.unit = 'PERCENTAGE' AND ye.price > 0
                        AND $validInWindow
                  )
                GROUP BY b.yacht_id
            )
            SELECT y.id AS yacht_id,
                   CASE WHEN y.build_year BETWEEN $MIN_BUILD_YEAR AND extract(year FROM CURRENT_DATE)::int + 1
                        THEN y.build_year::int END AS build_year,
                   NULLIF(btrim(mf.name), '') AS manufacturer,
                   NULLIF(btrim(m.name), '') AS model,
                   -- deposit is in the partner's currency; only EUR ones are comparable (the few USD boats are left out);
                   -- below $MIN_DEPOSIT EUR it is a placeholder (1 EUR), not a deposit
                   CASE WHEN y.deposit >= $MIN_DEPOSIT AND COALESCE(NULLIF(upper(btrim(y.deposit_currency)), ''), 'EUR') = 'EUR'
                        THEN y.deposit END AS deposit_eur,
                   sk.weekly AS skipper_weekly,
                   ob.weekly AS obligatory_weekly
            FROM boats b
            JOIN yacht y ON y.id = b.yacht_id
            LEFT JOIN model m ON m.id = y.model_id
            LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
            LEFT JOIN sk ON sk.yacht_id = b.yacht_id
            LEFT JOIN ob ON ob.yacht_id = b.yacht_id
            """.trimIndent()
    }

    companion object {
        const val MIN_BOATS = 10
        const val STATEMENT_TIMEOUT_SECONDS = 600
        const val MIN_BUILD_YEAR = 1950
        const val MIN_DEPOSIT = 100
        const val SKIPPER_MIN_WEEKLY = 500
        const val SKIPPER_MAX_WEEKLY = 7000

        /** extras.id of the canonical "Skipper" extra (R__1_04_extras_import.sql). */
        const val SKIPPER_EXTRAS_ID = 1

        /** Skipper-named rows that are not "a skipper for the week". */
        const val SKIPPER_EXCLUDE = "training|trainer|cook|hostess|advance|certificate|licen[cs]e"

        /** Deposit-like obligatory items: not a charter cost (refundable) or a deposit substitute. */
        const val DEPOSIT_LIKE = "deposit|caution|kaution|waiver|insurance"

        private val COUNTRY_CODE = Regex("^[A-Z]{2}$")

        /** The same did can map a marina more than once (two regions, sibling spelling) — DISTINCT via UNION. */
        val SCOPE_SQL =
            """
            CREATE TEMP TABLE cf_scope ON COMMIT DROP AS
            WITH f AS (
                SELECT l.id, l.country_code,
                       translate(lower(trim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS k
                FROM location l
            ),
            used AS (SELECT f.* FROM f WHERE f.id IN (SELECT DISTINCT location_id FROM cf_week))
            SELECT 'c-' || c.id AS did, u.id AS location_id
            FROM used u JOIN country c ON c.code2 = u.country_code
            UNION
            SELECT 'r-' || r.id, u.id
            FROM used u
            JOIN location_region lr ON lr.location_id = u.id
            JOIN region r ON r.id = lr.region_id
            WHERE COALESCE(NULLIF(r.country_code, ''), u.country_code) = u.country_code
            UNION
            SELECT 'l-' || f.id, u.id
            FROM used u JOIN f ON f.k = u.k AND f.country_code = u.country_code
            """.trimIndent()

        val MEMBER_SQL =
            """
            CREATE TEMP TABLE cf_member ON COMMIT DROP AS
            SELECT DISTINCT s.did, w.yacht_id, w.vessel_type
            FROM cf_week w JOIN cf_scope s ON s.location_id = w.location_id
            WHERE w.has_bookable
            """.trimIndent()

        val KEY_SQL =
            """
            CREATE TEMP TABLE cf_key ON COMMIT DROP AS
            WITH d AS (SELECT did, count(*) AS n FROM cf_member GROUP BY did),
                 ok AS (SELECT did FROM d WHERE n >= :minBoats OR did LIKE 'c-%'),
                 t AS (SELECT did, vessel_type, count(*) AS n FROM cf_member GROUP BY did, vessel_type)
            SELECT did, NULL::varchar AS vessel_type FROM ok
            UNION ALL
            SELECT t.did, t.vessel_type FROM t JOIN ok ON ok.did = t.did WHERE t.n >= :minBoats
            """.trimIndent()

        /** vessel_type is NOT NULL on yacht, so NULL here always means the all-types grouping set. */
        private const val VT = "CASE WHEN GROUPING(vessel_type) = 1 THEN NULL ELSE vessel_type END AS vessel_type"

        val BOAT_STATS_SQL =
            """
            SELECT did, $VT,
                   count(*) AS boats,
                   percentile_disc(0.5) WITHIN GROUP (ORDER BY build_year) AS build_year_median,
                   count(build_year) AS build_year_n,
                   min(deposit_eur) AS deposit_min,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY deposit_eur) AS deposit_median,
                   max(deposit_eur) AS deposit_max,
                   count(deposit_eur) AS deposit_n,
                   percentile_cont(0.25) WITHIN GROUP (ORDER BY skipper_weekly) AS skipper_p25,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY skipper_weekly) AS skipper_median,
                   percentile_cont(0.75) WITHIN GROUP (ORDER BY skipper_weekly) AS skipper_p75,
                   count(skipper_weekly) AS skipper_n,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY obligatory_weekly) AS obligatory_median,
                   count(obligatory_weekly) AS obligatory_n
            FROM (SELECT m.did, m.vessel_type, b.* FROM cf_member m JOIN cf_boat b ON b.yacht_id = m.yacht_id) x
            GROUP BY GROUPING SETS ((did), (did, vessel_type))
            """.trimIndent()

        val MONTH_SQL =
            """
            SELECT did, $VT, month,
                   count(*) AS weeks,
                   count(*) FILTER (WHERE is_free) AS free_weeks,
                   count(price) AS priced,
                   percentile_cont(0.25) WITHIN GROUP (ORDER BY price) AS p25,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY price) AS median,
                   percentile_cont(0.75) WITHIN GROUP (ORDER BY price) AS p75
            FROM (SELECT s.did, w.vessel_type, to_char(w.date_from, 'YYYY-MM') AS month, w.is_free, w.price
                  FROM cf_week w
                  JOIN cf_scope s ON s.location_id = w.location_id
                  JOIN cf_member m ON m.did = s.did AND m.yacht_id = w.yacht_id) x
            GROUP BY GROUPING SETS ((did, month), (did, vessel_type, month))
            """.trimIndent()

        val CHECK_IN_SQL =
            """
            SELECT did, $VT, dow, count(*) AS weeks
            FROM (SELECT s.did, w.vessel_type, extract(isodow FROM w.date_from)::int AS dow
                  FROM cf_week w
                  JOIN cf_scope s ON s.location_id = w.location_id
                  JOIN cf_member m ON m.did = s.did AND m.yacht_id = w.yacht_id) x
            GROUP BY GROUPING SETS ((did, dow), (did, vessel_type, dow))
            """.trimIndent()

        val TOP_MODELS_SQL =
            """
            SELECT did, vessel_type, manufacturer, model, n
            FROM (
                SELECT g.*, row_number() OVER (PARTITION BY did, vessel_type ORDER BY n DESC, manufacturer, model) AS rn
                FROM (
                    SELECT did, $VT, manufacturer, model, count(*) AS n
                    FROM (SELECT m.did, m.vessel_type, b.manufacturer, b.model
                          FROM cf_member m JOIN cf_boat b ON b.yacht_id = m.yacht_id
                          WHERE b.model IS NOT NULL) x
                    GROUP BY GROUPING SETS ((did, manufacturer, model), (did, vessel_type, manufacturer, model))
                ) g
            ) r
            WHERE rn <= :top
            """.trimIndent()

        /**
         * Bases for c- / r- rows (an l- row IS one base). Same-name sibling marinas count as one base, shown under
         * the sibling most of the did's boats use; a boat based at several marinas counts once per marina.
         */
        val TOP_BASES_SQL =
            """
            WITH g AS (
                SELECT l.id AS location_id,
                       l.country_code || ':' || translate(lower(trim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS gkey
                FROM location l
                WHERE l.id IN (SELECT DISTINCT location_id FROM cf_week)
            ),
            x AS (
                SELECT s.did, yl.vessel_type, yl.yacht_id, yl.location_id, g.gkey
                FROM (SELECT DISTINCT yacht_id, vessel_type, location_id FROM cf_week WHERE has_bookable) yl
                JOIN cf_scope s ON s.location_id = yl.location_id
                JOIN g ON g.location_id = yl.location_id
                WHERE s.did NOT LIKE 'l-%'
            ),
            agg AS (
                SELECT did, $VT, gkey,
                       count(DISTINCT yacht_id) AS n,
                       mode() WITHIN GROUP (ORDER BY location_id) AS location_id
                FROM x
                GROUP BY GROUPING SETS ((did, gkey), (did, vessel_type, gkey))
            ),
            ranked AS (
                SELECT agg.*, row_number() OVER (PARTITION BY did, vessel_type ORDER BY n DESC, location_id) AS rn
                FROM agg
            )
            SELECT r.did, r.vessel_type, r.location_id, l.name, r.n
            FROM ranked r JOIN location l ON l.id = r.location_id
            WHERE r.rn <= :top
            """.trimIndent()
    }
}
