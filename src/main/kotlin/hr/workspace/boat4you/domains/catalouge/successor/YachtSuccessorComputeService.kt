package hr.workspace.boat4you.domains.catalouge.successor

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Service

/**
 * Recomputes yacht_successor (V9_73): for every retired partner boat (EXTERNAL, sys_active = false) the live listing of
 * the same physical boat, when exactly one can be named. Scheduler node only (data-sync, cusma3), see YachtSuccessorJob.
 *
 * The rule ([PICK_SQL], one set-based statement, ~0.2 s on production 7.10.2026):
 *  - same boat = same normalized name (case and punctuation folded, as V9_69), same build year, same home-base country
 *    and the same model - the same model_id, or, when the two partner systems keep the model under different ids
 *    ("Dufour 390 GL" / "Dufour 390 Grand Large"), the same first model word and a length within 0.3 m. Never another
 *    country or year. A name that is only the model or a placeholder ("Bavaria Cruiser 46", "no name") never matches:
 *    several boats of a fleet share it;
 *  - the successor must be what GET /public/yachts/{id} answers 200 for (YachtQueryingService.getValidYacht): active,
 *    agency active and not availability-blocked, and not at an inland base (V9_64). Inquiry-only boats and boats without
 *    a future offer keep their page, so they qualify;
 *  - chains: when no such listing matches directly, the match continues through other RETIRED listings of the boat (the
 *    model fallback is not transitive: a retired copy without a length still reaches a live copy under another model id
 *    through a retired copy with one), up to [MAX_HOPS] hops; the nearest live listings win. A boat page that is hidden
 *    for another reason (agency off) ends the chain. UNION on (origin, node, depth) plus the hop limit stop every cycle;
 *  - a live listing that the listings hide behind another copy of the same boat (yacht_listing_twin, V9_69/V9_70: the
 *    MMK and NauSys copies, the hand-verified pairs) gives way to the copy the listings show - when that copy has the
 *    same build year and country and is served. So two dual-source copies of one boat name ONE successor;
 *  - exactly one successor left -> stored. More than one: the one at the old boat's own base (same location id, or the
 *    same marina spelled alike, as V9_69's base key), if exactly one is there; otherwise none - a guess is worse than the
 *    404 the page gives today.
 *
 * Why a table and not a matview: the API reads one row per 1502, and a REFRESH would lock or rebuild it daily for ~3k
 * rows. Everything runs in ONE transaction on one connection: the result goes to a temp table, then the stored rows are
 * replaced (DELETE + INSERT, so readers keep the previous snapshot until COMMIT and never wait on a TRUNCATE lock). The
 * only lock on yacht is the ACCESS SHARE of a plain SELECT. A run that names no successor at all while rows are stored
 * is treated as broken input (an empty yacht or agency table after an incident) and rolled back.
 */
@Profile("data-sync")
@Service
class YachtSuccessorComputeService(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    data class Summary(
        val stored: Boolean,
        val resolved: Int,
        val viaChain: Int,
        val ambiguous: Int,
        val previous: Int,
        val millis: Long,
    )

    /** True while the table holds no row (fresh deploy) - the job then fills it at startup. */
    fun isEmpty(): Boolean = jdbcTemplate.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM yacht_successor)", Boolean::class.java) == true

    fun recompute(): Summary {
        val start = System.currentTimeMillis()
        val summary =
            jdbcTemplate.execute(
                ConnectionCallback<Summary> { conn ->
                    val autoCommit = conn.autoCommit
                    conn.autoCommit = false
                    try {
                        val result = computeAndReplace(JdbcTemplate(SingleConnectionDataSource(conn, true)), start)
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
            "Yacht successors: {} retired boats named a live listing ({} through a chain), {} left without (more than one " +
                "candidate), previous {} rows, {} ms{}",
            summary.resolved,
            summary.viaChain,
            summary.ambiguous,
            summary.previous,
            summary.millis,
            if (summary.stored) "" else " - NOT stored, previous rows kept",
        )
        return summary
    }

    private fun computeAndReplace(
        jdbc: JdbcTemplate,
        start: Long,
    ): Summary {
        // SET LOCAL: reset at COMMIT / ROLLBACK, so the pooled connection goes back clean. lock_timeout: the SELECT only
        // conflicts with an ALTER on one of the tables it reads (a deploy) - give up then, the next run comes tomorrow.
        jdbc.execute("SET LOCAL statement_timeout = '${STATEMENT_TIMEOUT_SECONDS}s'")
        jdbc.execute("SET LOCAL lock_timeout = '5s'")
        jdbc.execute(PICK_SQL)

        val resolved = jdbc.queryForObject("SELECT count(*) FROM ys_pick WHERE new_id IS NOT NULL", Int::class.java) ?: 0
        val viaChain = jdbc.queryForObject("SELECT count(*) FROM ys_pick WHERE new_id IS NOT NULL AND depth > 1", Int::class.java) ?: 0
        val ambiguous = jdbc.queryForObject("SELECT count(*) FROM ys_pick WHERE new_id IS NULL", Int::class.java) ?: 0
        val previous = jdbc.queryForObject("SELECT count(*) FROM yacht_successor", Int::class.java) ?: 0

        if (resolved == 0 && previous > 0) {
            log.error("Yacht successors: none computed vs {} stored - refusing to replace (empty yacht / agency data?)", previous)
            return Summary(false, resolved, viaChain, ambiguous, previous, System.currentTimeMillis() - start)
        }
        jdbc.update("DELETE FROM yacht_successor")
        jdbc.update("INSERT INTO yacht_successor (old_id, new_id, computed_at) SELECT old_id, new_id, now() FROM ys_pick WHERE new_id IS NOT NULL")
        return Summary(true, resolved, viaChain, ambiguous, previous, System.currentTimeMillis() - start)
    }

    companion object {
        /** ~0.2 s on production (7.10.2026); the limit only guards against a pathological plan. */
        private const val STATEMENT_TIMEOUT_SECONDS = 120

        /** Longest chain of retired listings followed to a live one. Also bounds the recursion (cycle protection). */
        const val MAX_HOPS = 4

        /**
         * ys_pick: one row per retired boat that matches at least one served listing of the same boat - old_id, the
         * hop count of the nearest served match (depth, 1 = direct), and new_id (NULL when more than one successor
         * remains). See the class KDoc for the rule.
         */
        val PICK_SQL =
            """
            CREATE TEMP TABLE ys_pick ON COMMIT DROP AS
            WITH RECURSIVE
            listing AS (
                -- every partner listing with what a successor is matched on; servable = GET /public/yachts/{id} -> 200
                SELECT y.id,
                       y.sys_active,
                       y.location_id,
                       l.country_code,
                       l.country_code || ':' || translate(lower(btrim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS base_key,
                       y.build_year,
                       y.model_id,
                       y.length,
                       regexp_replace(lower(btrim(y.name)), '[^[:alnum:]]', '', 'g') AS name_key,
                       regexp_replace(lower(split_part(btrim(m.name), ' ', 1)), '[^[:alnum:]]', '', 'g') AS model_word,
                       regexp_replace(lower(m.name), '[^[:alnum:]]', '', 'g') AS model_key,
                       regexp_replace(lower(COALESCE(mf.name, '') || m.name), '[^[:alnum:]]', '', 'g') AS full_model_key,
                       (y.sys_active AND COALESCE(a.active, false) AND NOT COALESCE(a.availability_blocked, false)
                            AND NOT COALESCE(l.inland, false)) AS servable
                FROM yacht y
                JOIN location l           ON l.id = y.location_id
                JOIN model m              ON m.id = y.model_id
                LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
                LEFT JOIN agency a        ON a.id = y.agency_id
                WHERE y.entry_type = 'EXTERNAL'
                  AND y.build_year IS NOT NULL
                  AND l.country_code IS NOT NULL
            ),
            boat AS (
                -- a real boat name: a letter, 2+ characters, not the model, not a placeholder (a fleet shares those)
                SELECT *
                FROM listing
                WHERE length(name_key) >= 2
                  AND name_key ~ '[a-z]'
                  AND name_key NOT IN (model_key, full_model_key, 'noname', 'unnamed', 'tba', 'tbd', 'new', 'yacht', 'boat')
            ),
            step AS (
                -- from a retired listing to every other listing of the same boat
                SELECT o.id AS from_id, c.id AS to_id
                FROM boat o
                JOIN boat c
                  ON c.name_key = o.name_key
                 AND c.build_year = o.build_year
                 AND c.country_code = o.country_code
                 AND c.id <> o.id
                 AND (c.model_id = o.model_id
                      OR (c.model_word = o.model_word AND c.model_word <> '' AND abs(c.length - o.length) <= 0.3))
                WHERE NOT o.sys_active
            ),
            walk (origin, node, depth) AS (
                -- steps leave only retired listings, so a chain runs through retired copies and ends at any other one
                SELECT from_id, to_id, 1 FROM step
                UNION
                SELECT w.origin, s.to_id, w.depth + 1
                FROM walk w
                JOIN step s ON s.from_id = w.node
                WHERE w.depth < $MAX_HOPS
                  AND s.to_id <> w.origin
            ),
            hit AS (
                SELECT w.origin, w.node, min(w.depth) AS depth
                FROM walk w
                JOIN boat t ON t.id = w.node AND t.servable
                GROUP BY w.origin, w.node
            ),
            nearest AS (
                SELECT h.origin, h.node, h.depth
                FROM (SELECT h.*, min(h.depth) OVER (PARTITION BY h.origin) AS best FROM hit h) h
                WHERE h.depth = h.best
            ),
            target AS (
                -- a copy the listings hide behind another copy of the same boat gives way to the copy shown
                SELECT n.origin,
                       n.depth,
                       COALESCE(k.id, n.node) AS target,
                       (c.location_id = o.location_id OR c.base_key = o.base_key) AS same_base
                FROM nearest n
                JOIN boat o ON o.id = n.origin
                JOIN boat c ON c.id = n.node
                LEFT JOIN yacht_listing_twin t ON t.yacht_id = n.node
                LEFT JOIN listing k ON k.id = t.canonical_yacht_id
                                   AND k.servable
                                   AND k.build_year = o.build_year
                                   AND k.country_code = o.country_code
            ),
            pick AS (
                SELECT origin,
                       min(depth) AS depth,
                       count(DISTINCT target) AS targets,
                       min(target) AS only_target,
                       count(DISTINCT target) FILTER (WHERE same_base) AS same_base_targets,
                       min(target) FILTER (WHERE same_base) AS same_base_target
                FROM target
                GROUP BY origin
            )
            SELECT origin AS old_id,
                   depth,
                   CASE WHEN targets = 1 THEN only_target
                        WHEN same_base_targets = 1 THEN same_base_target
                   END AS new_id
            FROM pick
            """.trimIndent()
    }
}
