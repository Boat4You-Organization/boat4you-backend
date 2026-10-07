package hr.workspace.boat4you.domains.catalouge.successor

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

/**
 * Recomputes yacht_successor (V9_73): for every retired partner boat (EXTERNAL, sys_active = false) the live listing of
 * the same physical boat, when exactly one can be named. Scheduler node only (data-sync, cusma3), see YachtSuccessorJob.
 *
 * The rule ([PICK_SQL], one set-based statement, ~0.2 s on production 7.10.2026):
 *  - same boat = same normalized name (case and punctuation folded, as V9_69), same build year, same home-base country
 *    and the same model - the same model_id, or, when the two partner systems keep the model under different ids
 *    ("Dufour 390 GL" / "Dufour 390 Grand Large"), the same first model word, the same model number and a length
 *    within 0.3 m (never Lagoon 46 / Lagoon 43). Never another country or year. A name that is only the model or a
 *    placeholder ("Bavaria Cruiser 46", "no name") never matches, nor a fleet label - a name one agency gives several
 *    of its live boats of one model and year on one partner system ("Moorings 4500 Club", "Sunsail 410 Classic");
 *  - another channel: another agency or the other partner system (as V9_69). Within one agency on one system two
 *    listings of one name are fleet mates - The Moorings retiring one "Moorings 4500 Club" at Cannigione does not make
 *    the one at Portorosa its successor - unless their registrations agree. Registrations (4+ digits; MMK sends one as
 *    the certificate, NauSys copies mostly carry none) that disagree are two boats ("Aria" EL-PIRAEUS-12480 / 12495);
 *  - the successor must be what GET /public/yachts/{id} answers 200 for (YachtQueryingService.getValidYacht): active,
 *    agency active and not availability-blocked, and not at an inland base (V9_64). Inquiry-only boats and boats without
 *    a future offer keep their page, so they qualify;
 *  - chains: when no such listing matches directly, the match continues through other RETIRED listings of the boat (a
 *    retired copy without a length reaches a live copy under another model id through a retired copy with one), up to
 *    [MAX_HOPS] hops; the nearest live listings win. The end of a chain must still be the old boat by the rule above -
 *    only a length missing on either end is bridged, the 0.3 m tolerance never adds up hop by hop. A boat page that is
 *    hidden for another reason (agency off) ends the chain. UNION on (origin, node, depth) plus the hop limit stop
 *    every cycle;
 *  - a live listing that the listings hide behind another copy of the same boat (yacht_listing_twin, V9_69/V9_70: the
 *    MMK and NauSys copies, the hand-verified pairs) gives way to the copy the listings show - when that copy has the
 *    same build year and country and is served. So two dual-source copies of one boat name ONE successor. When the copy
 *    shown is one the rule would not pair with the old boat (its own channel, another registration), the old boat gets
 *    none;
 *  - exactly one successor left -> stored. More than one: the one at the old boat's own base (same location id, or the
 *    same marina spelled alike, as V9_69's base key), if exactly one is there; otherwise none - a guess is worse than the
 *    404 the page gives today.
 *
 * Why a table and not a matview: the API reads one row per 1502, and a REFRESH would lock or rebuild it daily for ~3k
 * rows. Everything runs in ONE transaction on one connection: the result goes to a temp table, then the stored rows are
 * replaced (DELETE + INSERT, so readers keep the previous snapshot until COMMIT and never wait on a TRUNCATE lock). The
 * only lock on yacht is the ACCESS SHARE of a plain SELECT. A run that names fewer than half of the successors stored
 * (none at all included) is treated as broken input (an empty yacht or agency table after an incident, a partner
 * outage) and rolled back; YachtSuccessorJob reports rows older than two days. A real shrink of that size: DELETE FROM
 * yacht_successor, the next run fills the table again.
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

    /** When the stored rows were computed (null while the table is empty) - YachtSuccessorJob's staleness check. */
    fun latestComputedAt(): Instant? = jdbcTemplate.queryForObject("SELECT max(computed_at) FROM yacht_successor", Timestamp::class.java)?.toInstant()

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
                "candidate, or a doubtful one), previous {} rows, {} ms{}",
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

        if (resolved * 2 < previous) {
            log.error(
                "Yacht successors: {} computed vs {} stored - fewer than half, refusing to replace (empty / broken yacht or " +
                    "agency data, a partner outage?). A real shrink: DELETE FROM yacht_successor, the next run fills it again",
                resolved,
                previous,
            )
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

        /** Two partner systems' lengths of one boat under two model ids differ by up to this much (metres). */
        const val MAX_LENGTH_DIFF = 0.3

        /**
         * ys_pick: one row per retired boat that matches at least one served listing of the same boat - old_id, the
         * hop count of the nearest served match (depth, 1 = direct), and new_id (NULL when more than one successor
         * remains, or a doubtful one). See the class KDoc for the rule.
         */
        val PICK_SQL =
            """
            CREATE TEMP TABLE ys_pick ON COMMIT DROP AS
            WITH RECURSIVE
            source AS (
                -- the partner system a listing comes through (as V9_69): one agency's MMK and NauSys copies are two channels
                SELECT em.system_id AS yacht_id, min(em.external_system_id) AS system
                FROM external_mapping em
                WHERE em.type = 'Yacht'
                GROUP BY em.system_id
            ),
            listing AS (
                -- every partner listing with what a successor is matched on; servable = GET /public/yachts/{id} -> 200
                SELECT y.id,
                       y.sys_active,
                       y.agency_id,
                       s.system,
                       y.location_id,
                       l.country_code,
                       l.country_code || ':' || translate(lower(btrim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS base_key,
                       y.build_year,
                       y.model_id,
                       y.length,
                       -- the registration's digits ("EL-PIRAEUS-13270" -> 13270) when there are 4+ (MMK certificate)
                       CASE WHEN length(r.digits) >= 4 AND r.digits !~ '^0+$' THEN r.digits END AS reg,
                       regexp_replace(lower(btrim(y.name)), '[^[:alnum:]]', '', 'g') AS name_key,
                       regexp_replace(lower(split_part(btrim(m.name), ' ', 1)), '[^[:alnum:]]', '', 'g') AS model_word,
                       substring(m.name from '[0-9]+') AS model_number,
                       regexp_replace(lower(m.name), '[^[:alnum:]]', '', 'g') AS model_key,
                       regexp_replace(lower(COALESCE(mf.name, '') || m.name), '[^[:alnum:]]', '', 'g') AS full_model_key,
                       (y.sys_active AND COALESCE(a.active, false) AND NOT COALESCE(a.availability_blocked, false)
                            AND NOT COALESCE(l.inland, false)) AS servable
                FROM yacht y
                JOIN location l           ON l.id = y.location_id
                JOIN model m              ON m.id = y.model_id
                LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
                LEFT JOIN agency a        ON a.id = y.agency_id
                LEFT JOIN source s        ON s.yacht_id = y.id
                CROSS JOIN LATERAL (SELECT regexp_replace(COALESCE(y.registration_number, ''), '[^0-9]', '', 'g') AS digits) r
                WHERE y.entry_type = 'EXTERNAL'
                  AND y.build_year IS NOT NULL
                  AND l.country_code IS NOT NULL
            ),
            fleet AS (
                -- a name one agency gives several of its live boats of one model and year on one partner system names a
                -- class of boats, not one ("Moorings 4500 Club", "Sunsail 410 Classic"; V9_69 replayed 259 such pairs, all
                -- distinct boats). A name an agency merely reuses for another model or year ("Luna") stays a boat name
                SELECT DISTINCT country_code, name_key, build_year
                FROM listing
                WHERE sys_active
                GROUP BY agency_id, system, country_code, name_key, build_year, model_id
                HAVING count(*) > 1
            ),
            boat AS (
                -- a real boat name: a letter, 2+ characters, not the model, not a placeholder, not a fleet label
                SELECT b.*
                FROM listing b
                WHERE length(b.name_key) >= 2
                  AND b.name_key ~ '[a-z]'
                  AND b.name_key NOT IN (b.model_key, b.full_model_key, 'noname', 'unnamed', 'tba', 'tbd', 'new', 'yacht', 'boat')
                  AND NOT EXISTS (SELECT 1 FROM fleet f
                                  WHERE f.country_code = b.country_code AND f.name_key = b.name_key AND f.build_year = b.build_year)
            ),
            pair AS (
                -- a retired listing against every other listing of its name, build year and country, and whether the two
                -- are one boat: the same model (model_id, or first model word + model number + length), another channel
                -- (agency or partner system) unless the registrations agree, no two registrations that disagree.
                -- direct = one step of a chain; reaches = the old boat (origin) and the end of a chain - the length
                -- tolerance holds between the two ends, only a length missing on either end is bridged by the chain
                SELECT o.id AS from_id,
                       c.id AS to_id,
                       COALESCE(x.other_boat_ok
                                AND (x.same_model OR (x.alike_model AND abs(c.length - o.length) <= $MAX_LENGTH_DIFF)),
                                false) AS direct,
                       COALESCE(x.other_boat_ok
                                AND (x.same_model OR (x.alike_model AND (c.length IS NULL OR o.length IS NULL
                                                                         OR abs(c.length - o.length) <= $MAX_LENGTH_DIFF))),
                                false) AS reaches
                FROM boat o
                JOIN boat c
                  ON c.name_key = o.name_key
                 AND c.build_year = o.build_year
                 AND c.country_code = o.country_code
                 AND c.id <> o.id
                CROSS JOIN LATERAL (
                    SELECT c.model_id = o.model_id AS same_model,
                           c.model_word = o.model_word AND c.model_word <> '' AND c.model_number = o.model_number AS alike_model,
                           (c.agency_id IS DISTINCT FROM o.agency_id OR c.system IS DISTINCT FROM o.system OR c.reg = o.reg)
                               AND (c.reg IS NULL OR o.reg IS NULL OR c.reg = o.reg) AS other_boat_ok
                ) x
                WHERE NOT o.sys_active
            ),
            walk (origin, node, depth) AS (
                -- steps leave only retired listings, so a chain runs through retired copies and ends at any other one
                SELECT from_id, to_id, 1 FROM pair WHERE direct
                UNION
                SELECT w.origin, p.to_id, w.depth + 1
                FROM walk w
                JOIN pair p ON p.from_id = w.node AND p.direct
                WHERE w.depth < $MAX_HOPS
                  AND p.to_id <> w.origin
            ),
            hit AS (
                -- a served listing reached that is still the old boat (for a direct step that holds by construction)
                SELECT w.origin, w.node, min(w.depth) AS depth
                FROM walk w
                JOIN boat t ON t.id = w.node AND t.servable
                JOIN pair e ON e.from_id = w.origin AND e.to_id = w.node AND e.reaches
                GROUP BY w.origin, w.node
            ),
            nearest AS (
                SELECT h.origin, h.node, h.depth
                FROM (SELECT h.*, min(h.depth) OVER (PARTITION BY h.origin) AS best FROM hit h) h
                WHERE h.depth = h.best
            ),
            target AS (
                -- a copy the listings hide behind another copy of the same boat gives way to the copy shown - unless the
                -- rule would not pair the copy shown with the old boat (its own channel: a fleet mate; another
                -- registration): then the target is doubtful (NULL) and the old boat gets none
                SELECT n.origin,
                       n.depth,
                       CASE WHEN k.id IS NULL THEN n.node
                            WHEN (k.agency_id IS DISTINCT FROM o.agency_id OR k.system IS DISTINCT FROM o.system OR k.reg = o.reg)
                                 AND (k.reg IS NULL OR o.reg IS NULL OR k.reg = o.reg) THEN k.id
                       END AS target,
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
                       bool_or(target IS NULL) AS doubtful,
                       count(DISTINCT target) AS targets,
                       min(target) AS only_target,
                       count(DISTINCT target) FILTER (WHERE same_base) AS same_base_targets,
                       min(target) FILTER (WHERE same_base) AS same_base_target
                FROM target
                GROUP BY origin
            )
            SELECT origin AS old_id,
                   depth,
                   CASE WHEN doubtful THEN NULL
                        WHEN targets = 1 THEN only_target
                        WHEN same_base_targets = 1 THEN same_base_target
                   END AS new_id
            FROM pick
            """.trimIndent()
    }
}
