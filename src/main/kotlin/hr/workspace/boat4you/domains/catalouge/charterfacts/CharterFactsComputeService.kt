package hr.workspace.boat4you.domains.catalouge.charterfacts

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.BaseCount
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.BoatStats
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.Key
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.ModelCount
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.MonthStats
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.Window
import hr.workspace.boat4you.domains.catalouge.services.MarinaPlaces
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
import java.time.ZoneOffset

/**
 * Recomputes every `charter_facts` row (landing-page inventory facts per destination and boat type).
 *
 * Runs ONLY on the scheduler node (data-sync profile, cusma3): cusma2 is the only API node and has an OOM history,
 * so nothing here may run per request. The whole run is set-based — a handful of statements over the whole
 * promoted-country population, grouped by did / vessel type with GROUPING SETS — never a per-destination loop.
 *
 * Dates come from [Window] (one `today`, passed in, never the database's CURRENT_DATE, so a run is reproducible):
 * membership looks 12 months ahead from today, month figures cover the 12 FULL calendar months after it.
 *
 * Population (mirrors the search, R__1_03 + YachtQueryingService):
 *  - EXTERNAL yachts, sys_active, agency active and not availability_blocked (dual-source agencies are synced from
 *    their one primary source), minus the second listings of a boat another channel lists (yacht_listing_twin, V9_69),
 *    so each boat counts once;
 *  - weekly offers = exactly 7 nights, date_from from today, pickup marina in a promoted country;
 *  - one row per yacht-week (a week can have a BAREBOAT and a CREWED row, or a one-way variant): the round-trip,
 *    bookable (not UNAVAILABLE), cheapest row names the base, the price is the cheapest non-UNAVAILABLE client_price (EUR,
 *    what the listing shows), and the week counts as available when any of its rows is FREE, and bookable when any of
 *    its rows is not UNAVAILABLE;
 *  - price figures use only trusted weeks: at least [CharterFactsMath.MIN_WEEK_PRICE] EUR, from a boat whose cheapest
 *    such week is at least [CharterFactsMath.OUTLIER_RATIO] of its dearest (the listing's typo guard) — Valencia's
 *    "10 EUR" weeks and Leonidas II's 294.50 EUR week never reach a median;
 *  - a boat belongs to a did only when it has at least one BOOKABLE week based there within 12 months — search hides
 *    UNAVAILABLE rows (YachtQueryingService, offerStatus <> UNAVAILABLE for every public caller), so a boat whose every
 *    week is UNAVAILABLE (withdrawn by the MMK reverifier, blocked by owner weeks) is not counted: the per-boat figures,
 *    models and bases all use members only. Month / check-in figures use all weeks of MEMBER boats (their UNAVAILABLE
 *    weeks stay in the availability denominators);
 *  - activeBoats is the LISTING's count (26.9.2026 audit B12: the facts tile said 3,814 under an H2 of 3,863): every
 *    yacht_search_view row the undated landing lists — a bookable offer of any length starting today or later (or a
 *    custom boat) picked up in the did — counted by the same scope rules;
 *  - did membership by pickup marina: c- = marina country (country.code2), r- = location_region with the search's
 *    own-country guard, l- = the marina and every row of the same physical place ([MarinaPlaces]: same spelling,
 *    one name inside the other, curated location_same_place pairs — vetoed by coordinates / city). Drop-off-only
 *    matches (one-way INTO the destination) are not counted — facts describe boats based there.
 *
 * Keys written: every promoted country (c-) with at least one boat, every r- / l- with >= [MIN_BOATS] boats, every
 * dual-source region pair ("r-187,r-19", REGION_PAIR_SCOPE_SQL) with >= [MIN_BOATS] boats, each for all types plus
 * every vessel type with >= [MIN_BOATS] boats in that did.
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
     * empty-result guard always applies: zero rows is never a legitimate outcome. [today] anchors every date of the
     * run (the job runs at 08:00 UTC, so the UTC date).
     */
    fun recompute(
        force: Boolean = false,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Summary {
        val start = System.currentTimeMillis()
        val window = Window.of(today)
        val summary =
            jdbcTemplate.execute(
                ConnectionCallback<Summary> { conn ->
                    val autoCommit = conn.autoCommit
                    conn.autoCommit = false
                    try {
                        val result = computeAndReplace(JdbcTemplate(SingleConnectionDataSource(conn, true)), start, force, window)
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
        window: Window,
    ): Summary {
        // SET LOCAL: reset automatically at COMMIT / ROLLBACK, so the pooled connection goes back clean.
        jdbc.execute("SET LOCAL statement_timeout = '${STATEMENT_TIMEOUT_SECONDS}s'")
        jdbc.execute("SET LOCAL lock_timeout = '5s'")
        jdbc.execute("SET LOCAL work_mem = '128MB'")
        jdbc.execute("SET LOCAL jit = off")

        val codes = countries.joinToString(",") { "'$it'" }
        val sql = Sql(window)
        jdbc.execute(sql.week(codes))
        jdbc.execute("ANALYZE cf_week")
        jdbc.execute(sql.listed(codes))
        createPlaces(jdbc)
        jdbc.execute(SCOPE_SQL)
        jdbc.execute(REGION_PAIR_SCOPE_SQL)
        jdbc.execute(sql.member())
        jdbc.execute(KEY_SQL.replace(":minBoats", MIN_BOATS.toString()))
        jdbc.execute("DELETE FROM cf_scope s WHERE NOT EXISTS (SELECT 1 FROM cf_key k WHERE k.did = s.did)")
        jdbc.execute("DELETE FROM cf_member m WHERE NOT EXISTS (SELECT 1 FROM cf_key k WHERE k.did = m.did)")
        jdbc.execute(sql.boat())
        listOf("cf_scope", "cf_member", "cf_boat", "cf_listed").forEach { jdbc.execute("ANALYZE $it") }

        val weeks = jdbc.queryForObject("SELECT count(*) FROM cf_week", Long::class.java) ?: 0L
        val boats = jdbc.queryForObject("SELECT count(DISTINCT yacht_id) FROM cf_member", Long::class.java) ?: 0L

        val keys = jdbc.query("SELECT did, vessel_type FROM cf_key") { rs, _ -> Key(rs.getString(1), rs.getString(2)) }
        val boatStats = jdbc.query(BOAT_STATS_SQL) { rs, _ -> key(rs) to boatStats(rs) }.toMap()
        val listed = jdbc.query(LISTED_COUNT_SQL) { rs, _ -> key(rs) to rs.getLong("boats") }.toMap()
        val months = jdbc.query(sql.months()) { rs, _ -> key(rs) to monthStats(rs) }.groupBy({ it.first }, { it.second })
        val panelMonths = panelMonths(jdbc, sql, months)
        val checkIns =
            jdbc
                .query(sql.checkIns()) { rs, _ -> Triple(key(rs), rs.getInt("dow"), rs.getLong("weeks")) }
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, v) -> v.toMap() }
        val models =
            jdbc
                .query(TOP_MODELS_SQL.replace(":top", (CharterFactsMath.TOP_MODELS * CANDIDATE_FACTOR).toString())) { rs, _ ->
                    key(rs) to ModelCount(rs.getString("manufacturer"), rs.getString("model"), rs.getLong("n"))
                }.groupBy({ it.first }, { it.second })
        val bases =
            jdbc
                .query(sql.topBases().replace(":top", (CharterFactsMath.TOP_BASES * CANDIDATE_FACTOR).toString())) { rs, _ ->
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
                            listedBoats = listed[k] ?: stats.boats,
                            months = months[k].orEmpty(),
                            panelMonths = panelMonths[k].orEmpty(),
                            checkInDays = checkIns[k].orEmpty(),
                            topModels = models[k].orEmpty(),
                            topBases = bases[k].orEmpty(),
                            boatTypeMix = if (k.vesselType == null) typeMix[k.did].orEmpty() else null,
                            windowFrom = window.monthsFrom,
                            windowTo = window.monthsTo.minusDays(1),
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

    /**
     * Like-for-like month prices (B11): per key, the months [CharterFactsMath.priceMonths] shows, priced over the panel
     * of boats that have a trusted price in EVERY one of them. A month then reads dearer only because the same boats
     * cost more in it — never because only the big crewed yachts publish December prices.
     */
    private fun panelMonths(
        jdbc: JdbcTemplate,
        sql: Sql,
        months: Map<Key, List<MonthStats>>,
    ): Map<Key, List<MonthStats>> {
        val chosen = months.flatMap { (k, m) -> CharterFactsMath.priceMonths(m).map { month -> arrayOf<Any?>(k.did, k.vesselType, month) } }
        jdbc.execute("CREATE TEMP TABLE cf_key_month (did varchar(24) NOT NULL, vessel_type varchar(31), month varchar(7) NOT NULL) ON COMMIT DROP")
        jdbc.batchUpdate("INSERT INTO cf_key_month (did, vessel_type, month) VALUES (?, ?, ?)", chosen)
        jdbc.execute("ANALYZE cf_key_month")
        return jdbc.query(sql.panel()) { rs, _ -> key(rs) to monthStats(rs) }.groupBy({ it.first }, { it.second })
    }

    /**
     * `cf_place`: the physical place of every marina the run can see (pickups of the weekly and listed offers, plus
     * every home-base marina the location list offers), by [MarinaPlaces] — the same rules as the location autocomplete
     * merge, so a facts key, its landing and its "main bases" row mean the same boats. rep_id names the place.
     */
    private fun createPlaces(jdbc: JdbcTemplate) {
        val marinas =
            jdbc.query(
                """
                SELECT l.id, l.name, l.country_code, l.city, l.lat, l.lon
                FROM location l
                WHERE l.name IS NOT NULL
                  AND (l.id IN (SELECT location_id FROM cf_week UNION SELECT location_id FROM cf_listed)
                       OR EXISTS (SELECT 1 FROM yacht y WHERE y.location_id = l.id))
                """.trimIndent(),
            ) { rs, _ ->
                MarinaPlaces.Marina(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    countryCode = rs.getString("country_code"),
                    city = rs.getString("city"),
                    lat = rs.getBigDecimal("lat")?.toDouble(),
                    lon = rs.getBigDecimal("lon")?.toDouble(),
                )
            }
        val curated =
            jdbc.query("SELECT location_id, same_as_location_id FROM location_same_place") { rs, _ -> rs.getLong(1) to rs.getLong(2) }
        val placeOf = MarinaPlaces.placeIds(marinas, curated)
        val byId = marinas.associateBy { it.id }
        val repOf =
            placeOf.entries
                .groupBy({ it.value }, { byId.getValue(it.key) })
                .mapValues { (_, members) -> MarinaPlaces.representative(members).id }
        jdbc.execute("CREATE TEMP TABLE cf_place (location_id bigint PRIMARY KEY, place_id bigint NOT NULL, rep_id bigint NOT NULL) ON COMMIT DROP")
        jdbc.batchUpdate(
            "INSERT INTO cf_place (location_id, place_id, rep_id) VALUES (?, ?, ?)",
            placeOf.map { (id, place) -> arrayOf<Any>(id, place, repOf.getValue(place)) },
        )
        jdbc.execute("ANALYZE cf_place")
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
            boats = rs.getLong("boats"),
            pricedBoats = rs.getLong("priced_boats"),
        )

    /** The statements that depend on the run's dates. Dates are [LocalDate]s rendered as SQL literals (no injection). */
    private class Sql(
        w: Window,
    ) {
        private val today = lit(w.today)
        private val membershipTo = lit(w.membershipTo)
        private val monthsFrom = lit(w.monthsFrom)
        private val monthsTo = lit(w.monthsTo)
        private val weeksTo = lit(w.weeksTo)
        private val year = w.today.year

        private fun lit(d: LocalDate) = "DATE '$d'"

        fun week(codes: String) =
            """
            CREATE TEMP TABLE cf_week ON COMMIT DROP AS
            WITH wk AS (
                SELECT DISTINCT ON (o.yacht_id, o.date_from)
                       o.yacht_id,
                       o.date_from,
                       o.location_from AS location_id,
                       y.vessel_type,
                       bool_or(o.status = 'FREE') OVER yw AS is_free,
                       bool_or(o.status <> 'UNAVAILABLE') OVER yw AS has_bookable,
                       min(o.client_price) FILTER (WHERE o.status <> 'UNAVAILABLE' AND o.client_price > 0) OVER yw AS price
                FROM offer o
                JOIN yacht y     ON y.id = o.yacht_id AND y.entry_type = 'EXTERNAL' AND y.sys_active
                JOIN agency a    ON a.id = y.agency_id AND a.active AND NOT a.availability_blocked
                JOIN location lf ON lf.id = o.location_from
                WHERE o.date_from >= $today
                  AND o.date_from < $weeksTo
                  AND o.date_to = o.date_from + 7
                  -- one physical boat once: a second listing of it (another channel) is not a second boat (B17)
                  AND NOT EXISTS (SELECT 1 FROM yacht_listing_twin t WHERE t.yacht_id = o.yacht_id)
                  AND o.status NOT IN ('UNKNOWN', 'CANCELLED', 'INFO')
                  AND lf.country_code IN ($codes)
                WINDOW yw AS (PARTITION BY o.yacht_id, o.date_from)
                ORDER BY o.yacht_id, o.date_from, (o.location_from = o.location_to) DESC, (o.status <> 'UNAVAILABLE') DESC,
                         o.client_price ASC NULLS LAST, o.id
            ),
            -- The price grid of each boat over its placeholder-free weeks: a cheapest week below OUTLIER_RATIO of the
            -- dearest one is a partner typo, and the whole grid is then not trusted for a price figure.
            grid AS (
                SELECT yacht_id, min(price) AS lo, max(price) AS hi
                FROM wk
                WHERE price >= ${CharterFactsMath.MIN_WEEK_PRICE}
                GROUP BY yacht_id
            )
            SELECT wk.*,
                   CASE WHEN wk.price >= ${CharterFactsMath.MIN_WEEK_PRICE} AND grid.lo >= grid.hi * ${CharterFactsMath.OUTLIER_RATIO}
                        THEN wk.price END AS stat_price
            FROM wk
            LEFT JOIN grid ON grid.yacht_id = wk.yacht_id
            """.trimIndent()

        /**
         * What the undated landing lists (B12): the matview rows the public search keeps — not UNAVAILABLE, starting
         * today or later (custom boats have no dates), not a second listing of a boat (B17) — picked up in a promoted
         * country.
         */
        fun listed(codes: String) =
            """
            CREATE TEMP TABLE cf_listed ON COMMIT DROP AS
            SELECT DISTINCT v.id AS yacht_id, v.location_from AS location_id, v.vessel_type::varchar AS vessel_type
            FROM yacht_search_view v
            WHERE v.offer_status <> 'UNAVAILABLE'
              AND (v.date_from IS NULL OR v.date_from >= $today)
              AND v.country_code IN ($codes)
              AND v.location_from IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM yacht_listing_twin t WHERE t.yacht_id = v.id)
            """.trimIndent()

        fun member() =
            """
            CREATE TEMP TABLE cf_member ON COMMIT DROP AS
            SELECT DISTINCT s.did, w.yacht_id, w.vessel_type
            FROM cf_week w JOIN cf_scope s ON s.location_id = w.location_id
            WHERE w.has_bookable AND w.date_from < $membershipTo
            """.trimIndent()

        fun months() =
            """
            SELECT did, $VT, month,
                   count(*) AS weeks,
                   count(*) FILTER (WHERE is_free) AS free_weeks,
                   count(DISTINCT yacht_id) AS boats,
                   count(stat_price) AS priced,
                   count(DISTINCT yacht_id) FILTER (WHERE stat_price IS NOT NULL) AS priced_boats,
                   percentile_cont(0.25) WITHIN GROUP (ORDER BY stat_price) AS p25,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY stat_price) AS median,
                   percentile_cont(0.75) WITHIN GROUP (ORDER BY stat_price) AS p75
            FROM (SELECT s.did, w.vessel_type, w.yacht_id, to_char(w.date_from, 'YYYY-MM') AS month, w.is_free, w.stat_price
                  FROM cf_week w
                  JOIN cf_scope s ON s.location_id = w.location_id
                  JOIN cf_member m ON m.did = s.did AND m.yacht_id = w.yacht_id
                  WHERE w.date_from >= $monthsFrom AND w.date_from < $monthsTo) x
            GROUP BY GROUPING SETS ((did, month), (did, vessel_type, month))
            """.trimIndent()

        /**
         * Price figures of the chosen months (cf_key_month, vessel_type NULL = all types) over the key's panel: boats
         * with a trusted price in every chosen month. Weeks, not boats, are the values (as in [months]).
         */
        fun panel() =
            """
            WITH bm AS (
                SELECT k.did, k.vessel_type, w.yacht_id, k.month, w.stat_price
                FROM cf_week w
                JOIN cf_scope s ON s.location_id = w.location_id
                JOIN cf_member m ON m.did = s.did AND m.yacht_id = w.yacht_id
                JOIN cf_key_month k ON k.did = s.did AND (k.vessel_type IS NULL OR k.vessel_type = w.vessel_type)
                                   AND k.month = to_char(w.date_from, 'YYYY-MM')
                WHERE w.stat_price IS NOT NULL AND w.date_from >= $monthsFrom AND w.date_from < $monthsTo
            ),
            need AS (SELECT did, vessel_type, count(*) AS months FROM cf_key_month GROUP BY did, vessel_type),
            panel AS (
                SELECT bm.did, bm.vessel_type, bm.yacht_id
                FROM bm
                JOIN need n ON n.did = bm.did AND n.vessel_type IS NOT DISTINCT FROM bm.vessel_type
                GROUP BY bm.did, bm.vessel_type, bm.yacht_id, n.months
                HAVING count(DISTINCT bm.month) = n.months
            )
            SELECT bm.did, bm.vessel_type, bm.month,
                   0::bigint AS weeks,
                   0::bigint AS free_weeks,
                   count(DISTINCT bm.yacht_id) AS boats,
                   count(*) AS priced,
                   count(DISTINCT bm.yacht_id) AS priced_boats,
                   percentile_cont(0.25) WITHIN GROUP (ORDER BY bm.stat_price) AS p25,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY bm.stat_price) AS median,
                   percentile_cont(0.75) WITHIN GROUP (ORDER BY bm.stat_price) AS p75
            FROM bm
            JOIN panel p ON p.did = bm.did AND p.vessel_type IS NOT DISTINCT FROM bm.vessel_type AND p.yacht_id = bm.yacht_id
            GROUP BY bm.did, bm.vessel_type, bm.month
            """.trimIndent()

        fun checkIns() =
            """
            SELECT did, $VT, dow, count(*) AS weeks
            FROM (SELECT s.did, w.vessel_type, extract(isodow FROM w.date_from)::int AS dow
                  FROM cf_week w
                  JOIN cf_scope s ON s.location_id = w.location_id
                  JOIN cf_member m ON m.did = s.did AND m.yacht_id = w.yacht_id
                  WHERE w.date_from < $membershipTo) x
            GROUP BY GROUPING SETS ((did, dow), (did, vessel_type, dow))
            """.trimIndent()

        /**
         * Bases for c- / r- rows (an l- row IS one base), grouped by physical place (cf_place: "Marina Baotić" and
         * "Trogir, Yachtclub Seget (Marina Baotić)" are one base, named by the place's representative on every
         * landing); a boat based at several places counts once per place.
         */
        fun topBases() =
            """
            WITH x AS (
                SELECT s.did, yl.vessel_type, yl.yacht_id, p.place_id
                FROM (SELECT DISTINCT yacht_id, vessel_type, location_id
                      FROM cf_week WHERE has_bookable AND date_from < $membershipTo) yl
                JOIN cf_scope s ON s.location_id = yl.location_id
                JOIN cf_place p ON p.location_id = yl.location_id
                WHERE s.did NOT LIKE 'l-%'
            ),
            agg AS (
                SELECT did, $VT, place_id, count(DISTINCT yacht_id) AS n
                FROM x
                GROUP BY GROUPING SETS ((did, place_id), (did, vessel_type, place_id))
            ),
            ranked AS (
                SELECT agg.*, row_number() OVER (PARTITION BY did, vessel_type ORDER BY n DESC, place_id) AS rn
                FROM agg
            )
            SELECT r.did, r.vessel_type, p.rep_id AS location_id, l.name, r.n
            FROM ranked r
            JOIN (SELECT DISTINCT place_id, rep_id FROM cf_place) p ON p.place_id = r.place_id
            JOIN location l ON l.id = p.rep_id
            WHERE r.rn <= :top
            """.trimIndent()

        fun boat(): String {
            val m = CharterFactsMath.weeklyMultiplierSql("ye.unit")
            val validInWindow =
                "(ye.valid_to IS NULL OR ye.valid_to >= $today) AND (ye.valid_from IS NULL OR ye.valid_from < $membershipTo)"
            val validToday =
                "((ye.valid_from IS NULL OR ye.valid_from <= $today) AND (ye.valid_to IS NULL OR ye.valid_to >= $today))"
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
                       CASE WHEN y.build_year BETWEEN $MIN_BUILD_YEAR AND ${year + 1}
                            THEN y.build_year::int END AS build_year,
                       NULLIF(btrim(mf.name), '') AS manufacturer,
                       NULLIF(btrim(m.name), '') AS model,
                       -- a boat's own name filed as its model ("Aegean Alisa"): the model row is not a model
                       COALESCE(lower(btrim(y.name)) = lower(btrim(m.name)), false) AS name_is_model,
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
    }

    companion object {
        const val MIN_BOATS = 10
        const val STATEMENT_TIMEOUT_SECONDS = 600
        const val MIN_BUILD_YEAR = 1950
        const val MIN_DEPOSIT = 100
        const val SKIPPER_MIN_WEEKLY = 500
        const val SKIPPER_MAX_WEEKLY = 7000

        /** SQL returns this many times the shown top-N rows, so the payload's own filters still leave a full list. */
        private const val CANDIDATE_FACTOR = 3

        /** extras.id of the canonical "Skipper" extra (R__1_04_extras_import.sql). */
        const val SKIPPER_EXTRAS_ID = 1

        /** Skipper-named rows that are not "a skipper for the week". */
        const val SKIPPER_EXCLUDE = "training|trainer|cook|hostess|advance|certificate|licen[cs]e"

        /** Deposit-like obligatory items: not a charter cost (refundable) or a deposit substitute. */
        const val DEPOSIT_LIKE = "deposit|caution|kaution|waiver|insurance"

        private val COUNTRY_CODE = Regex("^[A-Z]{2}$")

        /**
         * Every location the run sees mapped to its dids. The same did can map a marina more than once (two regions,
         * sibling rows of one place) — DISTINCT via UNION. l- = every row of the location's physical place (cf_place).
         */
        val SCOPE_SQL =
            """
            CREATE TEMP TABLE cf_scope ON COMMIT DROP AS
            WITH used AS (
                SELECT l.id, l.country_code
                FROM location l
                WHERE l.id IN (SELECT location_id FROM cf_week UNION SELECT location_id FROM cf_listed)
            )
            SELECT 'c-' || c.id AS did, u.id AS location_id
            FROM used u JOIN country c ON c.code2 = u.country_code
            UNION
            SELECT 'r-' || r.id, u.id
            FROM used u
            JOIN location_region lr ON lr.location_id = u.id
            JOIN region r ON r.id = lr.region_id
            WHERE COALESCE(NULLIF(r.country_code, ''), u.country_code) = u.country_code
            UNION
            SELECT 'l-' || m.location_id, u.id
            FROM used u
            JOIN cf_place p ON p.location_id = u.id
            JOIN cf_place m ON m.place_id = p.place_id
            """.trimIndent()

        /**
         * Dual-source region pairs (26.9.2026 audit B13: "ionian region", 1,364 boats, had no facts block). One sailing
         * area often exists as two region rows, one per partner ("Ionian" r-187 from MMK, "Ionian Islands" r-19 from
         * NauSys), and the landing lists both (did=r-187,r-19). The pairs are the ones the location list and the web's
         * popular searches treat as one area: two listed regions of the same country (or one without a country) whose
         * names start with the same word ("Ionian" / "Ionian Islands", "Athens / Saronic Gulf" / "Athens area/Saronic/
         * Peloponese"). Their marinas are different rows per partner, so no place rule can find them. Each pair gets a
         * row keyed by both ids, sorted as strings and comma-joined ("r-187,r-19" - JavaScript's default sort), over
         * the union of their scopes; the ordinary key rules then apply.
         */
        val REGION_PAIR_SCOPE_SQL =
            """
            INSERT INTO cf_scope (did, location_id)
            WITH r AS (
                SELECT DISTINCT s.did,
                       lower(split_part(regexp_replace(btrim(g.name), '[/,]', ' ', 'g'), ' ', 1)) AS word,
                       NULLIF(g.country_code, '') AS country_code
                FROM cf_scope s
                JOIN region g ON 'r-' || g.id = s.did
                WHERE s.did LIKE 'r-%'
            ),
            pairs AS (
                SELECT a.did AS a, b.did AS b
                FROM r a
                JOIN r b ON b.word = a.word AND a.did < b.did
                WHERE a.word <> ''
                  AND (a.country_code IS NULL OR b.country_code IS NULL OR a.country_code = b.country_code)
            )
            SELECT DISTINCT pr.a || ',' || pr.b, s.location_id
            FROM pairs pr
            JOIN cf_scope s ON s.did IN (pr.a, pr.b)
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

        /** The listing's boat count per key (activeBoats), by the same did scope as every other figure. */
        val LISTED_COUNT_SQL =
            """
            SELECT did, $VT, count(DISTINCT yacht_id) AS boats
            FROM (SELECT s.did, li.vessel_type, li.yacht_id FROM cf_listed li JOIN cf_scope s ON s.location_id = li.location_id) x
            GROUP BY GROUPING SETS ((did), (did, vessel_type))
            """.trimIndent()

        /**
         * Model rows. A "model" that every boat using it carries as its own name ("Aegean Alisa") is not a model
         * (HAVING; bool_and, so one unnamed boat called "Bavaria Cruiser 46" never hides the real model); generic
         * vessel-type "models" and rows under 5 boats are dropped in [CharterFactsMath.buildPayload].
         */
        val TOP_MODELS_SQL =
            """
            SELECT did, vessel_type, manufacturer, model, n
            FROM (
                SELECT g.*, row_number() OVER (PARTITION BY did, vessel_type ORDER BY n DESC, manufacturer, model) AS rn
                FROM (
                    SELECT did, $VT, manufacturer, model, count(*) AS n
                    FROM (SELECT m.did, m.vessel_type, b.manufacturer, b.model, b.name_is_model
                          FROM cf_member m JOIN cf_boat b ON b.yacht_id = m.yacht_id
                          WHERE b.model IS NOT NULL) x
                    GROUP BY GROUPING SETS ((did, manufacturer, model), (did, vessel_type, manufacturer, model))
                    HAVING NOT bool_and(name_is_model)
                ) g
            ) r
            WHERE rn <= :top
            """.trimIndent()
    }
}
