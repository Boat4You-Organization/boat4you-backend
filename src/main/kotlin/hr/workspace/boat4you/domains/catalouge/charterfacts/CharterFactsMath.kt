package hr.workspace.boat4you.domains.catalouge.charterfacts

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pure helpers for the landing-page charter facts: `did` validation, extra-unit -> weekly conversion and the
 * payload assembly from the aggregate rows [CharterFactsComputeService] reads. No I/O, so every rule the page
 * shows (sample-size cut-offs, which months are comparable, cheapest / priciest / most-booked month, rounding) is
 * unit-testable.
 *
 * 26.9.2026 audit (B11): the block said "Most expensive month: September 2026" for Greece (10,878 EUR against
 * 4,989 EUR for August 2027) and "10 EUR" weeks for Valencia. Three causes, three rules here:
 *  - the current month was a bucket of its last few days (332 late-season offers against 10,137 in October):
 *    month figures are computed over FULL calendar months only ([Window]);
 *  - a thin month (only the few boats that publish winter prices) was ranked against a fully covered one, and every
 *    month described a different fleet (Croatia's November median above October's, Kornati motor yachts "dearest in
 *    November"): a month is shown only when at least [MONTH_COVERAGE] of the best-covered month's boats have a price in
 *    it (and at least [MIN_SAMPLE] boats), and the shown months are then priced LIKE FOR LIKE - over the same boats,
 *    those with a price in every shown month (the compute service's panel) - so a month can only read dearer because
 *    the same boats cost more in it;
 *  - placeholder and typo prices were medians: a week below [MIN_WEEK_PRICE] EUR, and every week of a boat whose
 *    cheapest week is below [OUTLIER_RATIO] of its dearest (the same guard as the listing's priceBasis=week), never
 *    enters a price figure (SQL side, see [CharterFactsComputeService]).
 */
object CharterFactsMath {
    /** `c-54` / `r-12` / `l-9001`. Length-capped so a crafted id never reaches the database as a huge string. */
    private val DID_PATTERN = Regex("""^[clr]-\d{1,12}$""")

    /** A figure computed from fewer values (boats) than this is noise on a landing page, so the field is left out. */
    const val MIN_SAMPLE = 5

    const val TOP_MODELS = 8
    const val TOP_BASES = 8

    /**
     * A month is shown (and ranked) only when at least this share of the best-covered month's boats has a price
     * (availability: a week) in it. Off-season months where only a handful of large or crewed boats publish prices
     * are not comparable with the season: their median describes another fleet.
     */
    val MONTH_COVERAGE: BigDecimal = BigDecimal("0.5")

    /** Cheapest / priciest month are only named when the dearest shown month is at least 10 % above the cheapest. */
    val MIN_PRICE_SPREAD: BigDecimal = BigDecimal("1.10")

    /** A 7-night charter below this (EUR) is a partner placeholder (10 EUR, 1 EUR), never a real week price. */
    const val MIN_WEEK_PRICE = 300

    /**
     * The listing's priceBasis=week typo guard (YachtQueryingService.WEEKLY_PRICE_OUTLIER_RATIO): a boat whose
     * cheapest week is below this share of its dearest week has a broken price grid (294.50 among 1,736-4,940), so
     * none of its weeks is used for a price figure.
     */
    val OUTLIER_RATIO: BigDecimal = BigDecimal("0.12")

    /**
     * "Models" that are a boat type, not a model (partner catalogues file some gulets and motor yachts under
     * "Gulet" / "Motoryacht" / "Luxury Motor Yacht"). Compared on [modelKey].
     */
    private val GENERIC_MODEL =
        Regex(
            "^(luxury|classic|traditional|wooden|custom|private)?" +
                "(motoryacht|motorboat|motorsailer|motorsailor|gulet|goelette|catamaran|powercatamaran|sailingyacht|sailingboat|" +
                "sailboat|yacht|superyacht|megayacht|trimaran|rib|speedboat|minicruiser|houseboat|boat|other|unknown|model)s?$",
        )

    fun isValidDid(did: String?): Boolean = did != null && DID_PATTERN.matches(did)

    private val REGION_DID = Regex("""^r-\d{1,12}$""")

    /**
     * The facts key of a request: one c- / r- / l- did as is, or a dual-source region pair ("r-19,r-187" in any order)
     * as its two ids sorted as strings and comma-joined ("r-187,r-19"). Null for anything else.
     */
    fun factsKey(did: String?): String? {
        if (did == null) return null
        if (isValidDid(did)) return did
        val parts = did.split(',')
        if (parts.size != 2 || parts.any { !REGION_DID.matches(it) } || parts[0] == parts[1]) return null
        return parts.sorted().joinToString(",")
    }

    /**
     * How many times an extra's price is charged for one 7-night charter, or null when that cannot be known
     * without the party size or a quantity (per person, per hour, per litre, percentage, unknown unit) — such
     * extras are left out of the weekly figures instead of being guessed.
     */
    fun weeklyMultiplier(unit: ExtrasUnitType): Int? =
        when (unit) {
            ExtrasUnitType.PER_NIGHT -> 7
            ExtrasUnitType.PER_WEEK,
            ExtrasUnitType.PER_BOOKING,
            ExtrasUnitType.PER_BOAT,
            ExtrasUnitType.AMOUNT,
            -> 1
            else -> null
        }

    /** [weeklyMultiplier] as a SQL CASE over a varchar enum column (V1_90 stores the enum name), NULL otherwise. */
    fun weeklyMultiplierSql(column: String): String =
        ExtrasUnitType.entries
            .mapNotNull { unit -> weeklyMultiplier(unit)?.let { "WHEN '${unit.name}' THEN $it" } }
            .joinToString(separator = " ", prefix = "(CASE $column ", postfix = " END)")

    /** Lower case, letters and digits only: "Luxury Motor Yacht" -> "luxurymotoryacht". */
    fun modelKey(name: String?): String = (name ?: "").lowercase().replace(NON_ALNUM, "")

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")

    /**
     * True for a model row that must not be listed as a model: a vessel-type word ("Gulet", "Motoryacht", "Luxury
     * Motor Yacht") or the manufacturer's own name repeated as the model. Boat names filed as models ("Aegean Alisa")
     * are caught in SQL (the model name equals the name of a boat using it) and by the [MIN_SAMPLE] boats rule.
     */
    fun isGenericModel(
        manufacturer: String?,
        model: String,
    ): Boolean {
        val key = modelKey(model)
        return key.isEmpty() || GENERIC_MODEL.matches(key) || key == modelKey(manufacturer)
    }

    /**
     * The date ranges one run works on, all derived from [today]:
     *  - membership: a boat belongs to a did when it has a bookable week starting in [today, membershipTo);
     *  - months: price / availability figures cover the 12 FULL calendar months [monthsFrom, monthsTo). The current
     *    month counts only when today is its first day — otherwise its bucket would hold the last few (late-season,
     *    left-over) weeks only and rank as the "most expensive month" (B11).
     */
    data class Window(
        val today: LocalDate,
        val membershipTo: LocalDate,
        val monthsFrom: LocalDate,
        val monthsTo: LocalDate,
    ) {
        /** Offers the run reads: from today up to the end of the last full month. */
        val weeksTo: LocalDate get() = maxOf(membershipTo, monthsTo)

        companion object {
            fun of(today: LocalDate): Window {
                val monthsFrom = if (today.dayOfMonth == 1) today else today.plusMonths(1).withDayOfMonth(1)
                return Window(today, today.plusMonths(12), monthsFrom, monthsFrom.plusMonths(12))
            }
        }
    }

    // ---- aggregate rows (one per did / vessel type; vesselType null = all types) ----

    data class Key(
        val did: String,
        val vesselType: String?,
    )

    data class BoatStats(
        /** Boats with a bookable 7-night week based here: the sample behind every per-boat figure. */
        val boats: Long,
        val buildYearMedian: Int?,
        val buildYearN: Long,
        val depositMin: BigDecimal?,
        val depositMedian: BigDecimal?,
        val depositMax: BigDecimal?,
        val depositN: Long,
        val skipperP25: BigDecimal?,
        val skipperMedian: BigDecimal?,
        val skipperP75: BigDecimal?,
        val skipperN: Long,
        val obligatoryMedian: BigDecimal?,
        val obligatoryN: Long,
    )

    data class MonthStats(
        val month: String,
        /** Yacht-weeks of member boats starting this month (availability denominator). */
        val weeks: Long,
        val freeWeeks: Long,
        /** Weeks with a trusted price (floor + outlier guard). */
        val priced: Long,
        val p25: BigDecimal?,
        val median: BigDecimal?,
        val p75: BigDecimal?,
        /** Distinct member boats with a week this month. */
        val boats: Long,
        /** Distinct boats with a trusted price this month. */
        val pricedBoats: Long,
    )

    data class ModelCount(
        val manufacturer: String?,
        val model: String,
        val count: Long,
    )

    data class BaseCount(
        val locationId: Long,
        val name: String,
        val count: Long,
    )

    data class Inputs(
        val boats: BoatStats,
        /**
         * Boats the listing shows for this did / type: EXTERNAL boats with a bookable offer (any length) starting
         * today or later, picked up here, plus the custom boats based here — the landing's "N boats available"
         * definition (B12). Null = same as [BoatStats.boats] (unit tests).
         */
        val listedBoats: Long? = null,
        /** Every full month: availability, and the price coverage [priceMonths] chooses the shown months by. */
        val months: List<MonthStats> = emptyList(),
        /**
         * The like-for-like price figures of the months [priceMonths] chose: over the panel of boats priced in every
         * one of them (same `pricedBoats` in each). Null = price [months] directly (unit tests of the rules).
         */
        val panelMonths: List<MonthStats>? = null,
        /** ISO day of week (1 = Monday) -> weeks starting that day. */
        val checkInDays: Map<Int, Long> = emptyMap(),
        val topModels: List<ModelCount> = emptyList(),
        val topBases: List<BaseCount> = emptyList(),
        /** Only for all-types rows: vessel type -> boats. */
        val boatTypeMix: Map<String, Long>? = null,
        val windowFrom: LocalDate,
        val windowTo: LocalDate,
    )

    /** The months a key's price table shows: enough boats with a trusted price, measured against the best month. */
    fun priceMonths(months: List<MonthStats>): List<String> =
        comparableMonths(months.filter { it.pricedBoats > 0 }) { it.pricedBoats }.map { it.month }

    /** Months whose [count] is at least [MIN_SAMPLE] and at least [MONTH_COVERAGE] of the best month's. */
    fun comparableMonths(
        months: List<MonthStats>,
        count: (MonthStats) -> Long,
    ): List<MonthStats> {
        val best = months.maxOfOrNull(count) ?: return emptyList()
        val floor = MONTH_COVERAGE.multiply(BigDecimal(best))
        return months
            .filter { count(it) >= MIN_SAMPLE && BigDecimal(count(it)) >= floor }
            .sortedBy { it.month }
    }

    /**
     * The JSON object stored in `charter_facts.payload`. Field order is stable (LinkedHashMap) so a diff between
     * two nights reads cleanly. Every field except activeBoats / boatsWithWeeklyPrices / currency / window is omitted
     * when its sample is below [MIN_SAMPLE].
     */
    fun buildPayload(inputs: Inputs): Map<String, Any> {
        val b = inputs.boats
        val out = linkedMapOf<String, Any>("activeBoats" to (inputs.listedBoats ?: b.boats), "boatsWithWeeklyPrices" to b.boats)

        val chosen = priceMonths(inputs.months).toSet()
        val priced =
            (inputs.panelMonths ?: inputs.months.filter { it.month in chosen })
                .filter { it.median != null && it.pricedBoats >= MIN_SAMPLE }
                .sortedBy { it.month }
        if (priced.isNotEmpty()) {
            out["priceByMonth"] =
                priced.map {
                    linkedMapOf(
                        "month" to it.month,
                        "p25" to euros(it.p25),
                        "median" to euros(it.median),
                        "p75" to euros(it.p75),
                        "offers" to it.priced,
                        "boats" to it.pricedBoats,
                    )
                }
            if (priced.size >= 2) {
                // Ties -> the earlier month, for both. Rounded euros, as shown: two months that read the same are equal.
                val cheapest = priced.minWith(compareBy<MonthStats> { euros(it.median) }.thenBy { it.month })
                val priciest = priced.maxWith(compareBy<MonthStats> { euros(it.median) }.thenByDescending { it.month })
                val spread = BigDecimal(euros(priciest.median)!!) >= MIN_PRICE_SPREAD.multiply(BigDecimal(euros(cheapest.median)!!))
                if (spread) {
                    out["cheapestMonth"] = cheapest.month
                    out["priciestMonth"] = priciest.month
                }
            }
        }

        val weeks = comparableMonths(inputs.months.filter { it.weeks > 0 }) { it.boats }
        if (weeks.isNotEmpty()) {
            out["availableShareByMonth"] =
                weeks.map { linkedMapOf("month" to it.month, "share" to share(it.freeWeeks, it.weeks), "weeks" to it.weeks) }
            if (weeks.size >= 2) {
                // Lowest FREE share = the month the fleet is most booked; ties -> the earlier month.
                out["mostBookedMonth"] =
                    weeks.minWith(compareBy<MonthStats> { share(it.freeWeeks, it.weeks) }.thenBy { it.month }).month
            }
        }

        if (b.skipperN >= MIN_SAMPLE && b.skipperMedian != null) {
            out["skipperWeekly"] =
                linkedMapOf(
                    "median" to euros(b.skipperMedian),
                    "p25" to euros(b.skipperP25),
                    "p75" to euros(b.skipperP75),
                    "n" to b.skipperN,
                )
        }
        // A 0 EUR median means most boats list no obligatory extra; "Obligatory extras per week 0 EUR" reads like the
        // old "7 days 0 EUR" bug, so the tile is left out instead (B13).
        if (b.obligatoryN >= MIN_SAMPLE && b.obligatoryMedian != null && (euros(b.obligatoryMedian) ?: 0L) > 0L) {
            out["obligatoryExtrasWeekly"] = linkedMapOf("median" to euros(b.obligatoryMedian), "n" to b.obligatoryN)
        }
        if (b.depositN >= MIN_SAMPLE && b.depositMedian != null) {
            out["deposit"] =
                linkedMapOf(
                    "min" to euros(b.depositMin),
                    "median" to euros(b.depositMedian),
                    "max" to euros(b.depositMax),
                    "n" to b.depositN,
                )
        }

        val checkInTotal = inputs.checkInDays.values.sum()
        if (checkInTotal >= MIN_SAMPLE) {
            out["checkInDays"] =
                inputs.checkInDays.entries
                    .filter { it.value > 0 && it.key in 1..7 }
                    .sortedBy { it.key }
                    .map { linkedMapOf("day" to DayOfWeek.of(it.key).name, "share" to share(it.value, checkInTotal)) }
        }

        if (b.buildYearN >= MIN_SAMPLE && b.buildYearMedian != null) {
            out["medianBuildYear"] = b.buildYearMedian
        }

        if (b.boats >= MIN_SAMPLE) {
            // Rows are counts of boats: the footnote's "fewer than 5 boats are left out" holds for them too
            // (Valencia listed "Aventura 45 · 1 boat").
            val models =
                inputs.topModels
                    .filter { it.count >= MIN_SAMPLE && !isGenericModel(it.manufacturer, it.model) }
                    .sortedWith(compareByDescending<ModelCount> { it.count }.thenBy { it.manufacturer }.thenBy { it.model })
                    .take(TOP_MODELS)
            if (models.isNotEmpty()) {
                out["topModels"] =
                    models.map { linkedMapOf("manufacturer" to it.manufacturer, "model" to it.model, "count" to it.count) }
            }
            val bases =
                inputs.topBases
                    .filter { it.count >= MIN_SAMPLE }
                    .sortedWith(compareByDescending<BaseCount> { it.count }.thenBy { it.locationId })
                    .take(TOP_BASES)
            if (bases.isNotEmpty()) {
                out["topBases"] =
                    bases.map {
                        linkedMapOf("locationId" to it.locationId, "name" to it.name, "count" to it.count, "did" to "l-${it.locationId}")
                    }
            }
        }

        inputs.boatTypeMix?.takeIf { it.isNotEmpty() }?.let { mix ->
            out["boatTypeMix"] =
                mix.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                    .map { linkedMapOf("vesselType" to it.key, "count" to it.value) }
        }

        out["currency"] = "EUR"
        out["windowFrom"] = inputs.windowFrom.toString()
        out["windowTo"] = inputs.windowTo.toString()
        return out
    }

    /** Whole euros, half-up — cents on a landing-page median are false precision. */
    fun euros(value: BigDecimal?): Long? = value?.setScale(0, RoundingMode.HALF_UP)?.toLong()

    /** part / total with 3 decimals (0.625), 0 for an empty total. */
    fun share(
        part: Long,
        total: Long,
    ): BigDecimal =
        if (total <= 0) {
            BigDecimal.ZERO.setScale(3)
        } else {
            BigDecimal(part).divide(BigDecimal(total), 3, RoundingMode.HALF_UP)
        }
}
