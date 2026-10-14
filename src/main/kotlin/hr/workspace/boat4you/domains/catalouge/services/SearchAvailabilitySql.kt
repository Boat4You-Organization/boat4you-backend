package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The real-price search rule (Mario 26.9.2026, decisions D1 / D5 of 14.10.2026; DESIGN.md WS-A) in one place: the
 * placeholder price floor, the D1 "same length, at most 3 days off" window, the multi-week tiling and the
 * "requested period taken" evidence clause.
 *
 * The listing builds its criteria from the constants and the [requestedPeriodTakenPositional] pattern here; the
 * native `yacht_search_view` queries (sidebar facets, relax suggestions) use the native fragments, so the page, the
 * count, the facet counts and the relax counts read one definition. Native fragments use the matview's unqualified
 * column names (a fragment inside a subquery over `yacht_search_view t` therefore reads `t`) and named parameters
 * ([P_FLOOR], [P_NIGHTS], ...); the caller binds exactly the parameters of the fragments it used ([Dated.params]).
 */
object SearchAvailabilitySql {
    /** A standard charter week, the tiling unit. */
    const val WEEK_NIGHTS = 7

    /**
     * D5: an offer priced below 300 EUR a week ([CharterFactsMath.MIN_WEEK_PRICE]) is a partner placeholder, never a
     * real price. The matview's `client_price` is PER DAY (R__1_03: `o.client_price / nights`), so the floor is
     * compared per day: 300 / 7. The only definition of the floor in the search code.
     */
    val MIN_WEEK_PRICE_PER_DAY: BigDecimal =
        BigDecimal(CharterFactsMath.MIN_WEEK_PRICE).divide(BigDecimal(WEEK_NIGHTS), 10, RoundingMode.HALF_UP)

    /** D1: a same-length offer may start at most this many days before or after the searched start. */
    const val MAX_SHIFT_DAYS = 3

    const val P_FLOOR = "priceFloor"
    const val P_NIGHTS = "d1Nights"
    const val P_SHIFT_FROM = "d1ShiftFrom"
    const val P_SHIFT_TO = "d1ShiftTo"
    const val P_WEEK_STARTS = "d1WeekStarts"
    const val P_WEEKS = "d1Weeks"
    const val P_START = "hardBlockStart"
    const val P_END = "hardBlockEnd"
    const val P_ZOMBIE_CUTOFF = "zombieCutoff"

    /** The default alias of the matview row in the native queries (they select FROM yacht_search_view unaliased). */
    const val VIEW = "yacht_search_view"

    /**
     * The effective rule of ONE request. [d1] = `SEARCH_PREDICATE=D1`; [floor] = `SEARCH_PRICE_FLOOR=ON`. An admin
     * caller (`legacyAvailability`) keeps both off - admins must see placeholder-priced boats to fix them - and a
     * sitemap request (idFrom / idTo / countryCodes) skips the floor (Mario 7.10.2026: never reduce boat URLs, until
     * he decides otherwise).
     */
    data class Mode(
        val d1: Boolean,
        val floor: Boolean,
    ) {
        companion object {
            /** Today's behaviour (both switches at their defaults). */
            val LEGACY = Mode(d1 = false, floor = false)

            fun of(
                snapshot: RealPriceSwitches.Snapshot,
                legacyAvailability: Boolean = false,
                sitemapRequest: Boolean = false,
            ): Mode =
                Mode(
                    d1 = !legacyAvailability && snapshot.searchPredicate == RealPriceSwitches.SearchPredicate.D1,
                    floor = !legacyAvailability && !sitemapRequest && snapshot.searchPriceFloor.isOn,
                )
        }
    }

    /** D1 shift: at most [MAX_SHIFT_DAYS], and less than the length, so a shifted row always overlaps the request. */
    fun shiftDays(nights: Int): Int = minOf(MAX_SHIFT_DAYS, nights - 1).coerceAtLeast(0)

    /**
     * The week starts of a request that real partner weeks may tile (more than one week, a whole number of weeks):
     * start, start + 7, ... Empty for any other length.
     */
    fun weekStarts(
        start: LocalDate,
        nights: Int,
    ): List<LocalDate> =
        if (nights > WEEK_NIGHTS && nights % WEEK_NIGHTS == 0) {
            (0 until nights / WEEK_NIGHTS).map { start.plusDays(WEEK_NIGHTS.toLong() * it) }
        } else {
            emptyList()
        }

    /** The floor in the non-custom arm: custom (admin-managed) yachts have no offer dates and always pass. */
    const val FLOOR_CLAUSE = "(date_from IS NULL OR client_price >= :$P_FLOOR)"

    /**
     * The D1 row clause of a dated search (custom rows pass): the searched length starting within the shift window,
     * or - for a tiled request - a priced week of the tiling whose yacht has a qualifying row for every week.
     * [rowVarying] = the caller's own row-varying filters (destination pickup, offer status, region country, charter
     * types, price), applied inside the tiled-ids subquery so "tiled" means "tiled by rows this query can list".
     */
    fun d1RowClause(
        tiled: Boolean,
        rowVarying: List<String>,
    ): String {
        val sameLength = "(number_of_days = :$P_NIGHTS AND date_from BETWEEN :$P_SHIFT_FROM AND :$P_SHIFT_TO)"
        val tile =
            if (tiled) {
                " OR (number_of_days = $WEEK_NIGHTS AND date_from IN (:$P_WEEK_STARTS) AND client_price >= :$P_FLOOR " +
                    "AND id IN (${tiledIds(rowVarying)}))"
            } else {
                ""
            }
        return "(date_from IS NULL OR $sameLength$tile)"
    }

    /**
     * Ids of the yachts whose qualifying weekly rows cover every week of the request. Uncorrelated (one hashed
     * sub-plan over at most 4 start dates); unqualified names in [rowVarying] resolve to `t`.
     */
    fun tiledIds(rowVarying: List<String>): String =
        "SELECT t.id FROM yacht_search_view t WHERE t.number_of_days = $WEEK_NIGHTS AND t.date_from IN (:$P_WEEK_STARTS) " +
            "AND t.client_price >= :$P_FLOOR" + rowVarying.joinToString("") { " AND $it" } +
            " GROUP BY t.id HAVING COUNT(DISTINCT t.date_from) = :$P_WEEKS"

    /**
     * The live hard block on the HULL of the request and the row's own window,
     * `[LEAST(start, row.date_from), GREATEST(end, row.date_to))`: a shifted row that runs into a partner
     * reservation is not listed (it would be rejected at booking). Exact and tile rows lie inside the request, so
     * their hull is the request. Postgres' LEAST / GREATEST ignore NULLs, so a custom row reads the request. A row
     * with a past, non-null option expiry is a freed hold ("zombie") and never blocks.
     */
    fun hullHardBlock(alias: String = VIEW): String =
        "NOT EXISTS (SELECT 1 FROM external_reservations er WHERE er.yacht_id = $alias.id " +
            "AND er.status IN ('RESERVATION', 'SERVICE') " +
            "AND er.date_from < GREATEST(:$P_END, $alias.date_to) AND er.date_to > LEAST(:$P_START, $alias.date_from) " +
            "AND (er.option_expiration IS NULL OR er.option_expiration > :$P_ZOMBIE_CUTOFF))"

    /**
     * The requested period is taken - on current evidence only: an offer row RESERVED / SERVICE overlapping the
     * request, or an UNAVAILABLE row with exactly the requested dates that carries evidence (MMK's own status for
     * that row - ext_status 6 owner / 10 regatta / 11 sleep aboard; NauSys writes text statuses, never these - or a
     * hide record for exactly those dates). An exact UNAVAILABLE row without evidence (a NauSys row left behind
     * after a cancelled reservation) does not hide the boat. Twin guard: a non-blocked row with the same dates
     * (a stale route duplicate) means the period is not taken. `external_system_id IN (1, 2)` only lets the
     * partner_period_check primary key serve the probe.
     */
    private const val TAKEN_TEMPLATE =
        "EXISTS (SELECT 1 FROM offer b WHERE b.yacht_id = {yacht} AND (" +
            "(b.status IN ('RESERVED', 'SERVICE') AND b.date_from < {end} AND b.date_to > {start}) " +
            "OR (b.status = 'UNAVAILABLE' AND b.date_from = {start} AND b.date_to = {end} " +
            "AND (b.ext_status IN ('6', '10', '11') " +
            "OR EXISTS (SELECT 1 FROM mmk_free_week_strike k WHERE k.yacht_id = b.yacht_id AND k.date_from = b.date_from " +
            "AND k.date_to = b.date_to AND k.hidden_on IS NOT NULL) " +
            "OR EXISTS (SELECT 1 FROM partner_period_check c WHERE c.external_system_id IN (1, 2) AND c.yacht_id = b.yacht_id " +
            "AND c.date_from = b.date_from AND c.date_to = b.date_to AND c.hidden_on IS NOT NULL)))) " +
            "AND NOT EXISTS (SELECT 1 FROM offer f WHERE f.yacht_id = b.yacht_id AND f.date_from = b.date_from " +
            "AND f.date_to = b.date_to AND f.status NOT IN ('RESERVED', 'SERVICE', 'UNAVAILABLE')))"

    private val TOKEN = Regex("\\{(yacht|start|end)}")

    /** [TAKEN_TEMPLATE] for a native query: the row alias' id and the request bound to [P_START] / [P_END]. */
    fun requestedPeriodTaken(alias: String = VIEW): String = TAKEN_TEMPLATE.replace("{yacht}", "$alias.id").replace("{start}", ":$P_START").replace("{end}", ":$P_END")

    /**
     * [TAKEN_TEMPLATE] for `HibernateCriteriaBuilder.sql(pattern, ...)`, whose `?` placeholders each take the next
     * argument: [Positional.args] names the argument of each `?` in order ("yacht", "start" or "end").
     */
    fun requestedPeriodTakenPositional(): Positional =
        Positional(
            pattern = TOKEN.replace(TAKEN_TEMPLATE, "?"),
            args = TOKEN.findAll(TAKEN_TEMPLATE).map { it.groupValues[1] }.toList(),
        )

    data class Positional(
        val pattern: String,
        val args: List<String>,
    )

    /**
     * The availability clauses of a DATED native search under D1, with the parameters they need. [rowVarying] feeds
     * the tiled-ids subquery (see [d1RowClause]).
     */
    fun d1Dated(
        start: LocalDate,
        end: LocalDate,
        rowVarying: List<String>,
        now: java.time.LocalDateTime,
        alias: String = VIEW,
    ): Dated {
        val nights = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()
        val shift = shiftDays(nights).toLong()
        val weeks = weekStarts(start, nights)
        val params =
            linkedMapOf<String, Any>(
                P_NIGHTS to nights,
                P_SHIFT_FROM to start.minusDays(shift),
                P_SHIFT_TO to start.plusDays(shift),
                P_START to start,
                P_END to end,
                P_ZOMBIE_CUTOFF to now,
            )
        if (weeks.isNotEmpty()) {
            params[P_WEEK_STARTS] = weeks
            params[P_WEEKS] = weeks.size
            params[P_FLOOR] = MIN_WEEK_PRICE_PER_DAY
        }
        return Dated(
            clauses =
                listOf(
                    d1RowClause(weeks.isNotEmpty(), rowVarying),
                    "($alias.date_from IS NULL OR ${hullHardBlock(alias)})",
                    "NOT ${requestedPeriodTaken(alias)}",
                ),
            params = params,
        )
    }

    /** Native clauses (ANDed, no leading AND) and their named parameters. */
    data class Dated(
        val clauses: List<String>,
        val params: Map<String, Any>,
    )
}
