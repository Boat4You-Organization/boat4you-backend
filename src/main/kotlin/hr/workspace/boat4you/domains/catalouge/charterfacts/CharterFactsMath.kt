package hr.workspace.boat4you.domains.catalouge.charterfacts

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pure helpers for the landing-page charter facts: `did` validation, extra-unit -> weekly conversion and the
 * payload assembly from the aggregate rows [CharterFactsComputeService] reads. No I/O, so every rule the page
 * shows (sample-size cut-offs, cheapest / priciest / most-booked month, rounding) is unit-testable.
 */
object CharterFactsMath {
    /** `c-54` / `r-12` / `l-9001`. Length-capped so a crafted id never reaches the database as a huge string. */
    private val DID_PATTERN = Regex("""^[clr]-\d{1,12}$""")

    /** A figure computed from fewer values than this is noise on a landing page, so the field is left out. */
    const val MIN_SAMPLE = 5

    const val TOP_MODELS = 8
    const val TOP_BASES = 8

    fun isValidDid(did: String?): Boolean = did != null && DID_PATTERN.matches(did)

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

    // ---- aggregate rows (one per did / vessel type; vesselType null = all types) ----

    data class Key(
        val did: String,
        val vesselType: String?,
    )

    data class BoatStats(
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
        val weeks: Long,
        val freeWeeks: Long,
        val priced: Long,
        val p25: BigDecimal?,
        val median: BigDecimal?,
        val p75: BigDecimal?,
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
        val months: List<MonthStats> = emptyList(),
        /** ISO day of week (1 = Monday) -> weeks starting that day. */
        val checkInDays: Map<Int, Long> = emptyMap(),
        val topModels: List<ModelCount> = emptyList(),
        val topBases: List<BaseCount> = emptyList(),
        /** Only for all-types rows: vessel type -> boats. */
        val boatTypeMix: Map<String, Long>? = null,
        val windowFrom: LocalDate,
        val windowTo: LocalDate,
    )

    /**
     * The JSON object stored in `charter_facts.payload`. Field order is stable (LinkedHashMap) so a diff between
     * two nights reads cleanly. Every field except activeBoats / currency / window is omitted when its sample is
     * below [MIN_SAMPLE].
     */
    fun buildPayload(inputs: Inputs): Map<String, Any> {
        val b = inputs.boats
        val out = linkedMapOf<String, Any>("activeBoats" to b.boats)

        val priced = inputs.months.filter { it.priced >= MIN_SAMPLE && it.median != null }.sortedBy { it.month }
        if (priced.isNotEmpty()) {
            out["priceByMonth"] =
                priced.map {
                    linkedMapOf(
                        "month" to it.month,
                        "p25" to euros(it.p25),
                        "median" to euros(it.median),
                        "p75" to euros(it.p75),
                        "offers" to it.priced,
                    )
                }
            if (priced.size >= 2) {
                out["cheapestMonth"] = priced.minWith(compareBy<MonthStats> { it.median }.thenBy { it.month }).month
                out["priciestMonth"] = priced.maxWith(compareBy<MonthStats> { it.median }.thenByDescending { it.month }).month
            }
        }

        val weeks = inputs.months.filter { it.weeks >= MIN_SAMPLE }.sortedBy { it.month }
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
        if (b.obligatoryN >= MIN_SAMPLE && b.obligatoryMedian != null) {
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
            val models = inputs.topModels.sortedWith(compareByDescending<ModelCount> { it.count }).take(TOP_MODELS)
            if (models.isNotEmpty()) {
                out["topModels"] =
                    models.map { linkedMapOf("manufacturer" to it.manufacturer, "model" to it.model, "count" to it.count) }
            }
            val bases = inputs.topBases.sortedWith(compareByDescending<BaseCount> { it.count }).take(TOP_BASES)
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
