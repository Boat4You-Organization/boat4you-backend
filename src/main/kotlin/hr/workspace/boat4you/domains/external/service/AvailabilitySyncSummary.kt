package hr.workspace.boat4you.domains.external.service

import java.time.LocalDate

/**
 * Per-row offer mutations made by the availability upsert (MMK + NauSys), counted instead of
 * INFO-logged one line per row (~40k lines/day on cusma3). One instance per (agency, year);
 * the run summary folds them together.
 */
class AvailabilityUpsertCounters {
    var flippedToOption = 0
    var setUnavailable = 0
    var synthesized = 0
    var cannotSynthesize = 0

    /** Sample (capped) of yacht ids with no FREE template to synthesize an OPTION from. */
    val cannotSynthesizeYachtIds = linkedSetOf<Long>()

    fun cannotSynthesize(yachtId: Long?) {
        cannotSynthesize++
        if (yachtId != null && cannotSynthesizeYachtIds.size < YACHT_ID_SAMPLE_LIMIT) cannotSynthesizeYachtIds.add(yachtId)
    }

    fun addAll(other: AvailabilityUpsertCounters) {
        flippedToOption += other.flippedToOption
        setUnavailable += other.setUnavailable
        synthesized += other.synthesized
        cannotSynthesize += other.cannotSynthesize
        other.cannotSynthesizeYachtIds.forEach { if (cannotSynthesizeYachtIds.size < YACHT_ID_SAMPLE_LIMIT) cannotSynthesizeYachtIds.add(it) }
    }

    override fun toString(): String =
        "flippedToOption=$flippedToOption setUnavailable=$setUnavailable synthesized=$synthesized cannotSynthesize=$cannotSynthesize"

    companion object {
        const val YACHT_ID_SAMPLE_LIMIT = 25
    }
}

/**
 * Result of one (agency, year) availability sync.
 * @param partnerRows size of the partner's occupancy response.
 * @param mappedRows how many of those rows resolved to one of OUR yachts (natural keys built).
 */
data class AvailabilitySyncResult(
    val partnerRows: Int,
    val mappedRows: Int,
    val outcome: ReconcileOutcome,
    val upsert: AvailabilityUpsertCounters,
) {
    /** The one real mapping-drift signal: partner HAS reservations, none map to our yachts. */
    val unmappedNonEmpty: Boolean get() = partnerRows > 0 && mappedRows == 0
}

/**
 * Accumulates one availability run (all agencies × years) into a single INFO summary line, so the
 * journal carries 8 lines/day instead of ~4,650 per-call WARNs. Empty responses are listed per year;
 * agency ids are printed only for the current year (a future year being empty is expected).
 */
class AvailabilitySyncRunSummary(
    private val partner: String,
    private val currentYear: Int = LocalDate.now().year,
) {
    var agencies = 0
    var calls = 0
    private var successes = 0
    val failures: Int get() = calls - successes
    private val emptyByYear = sortedMapOf<Int, MutableList<Long>>()
    private val unmapped = mutableListOf<String>()
    private val breakerTripped = mutableListOf<String>()
    var removed = 0
    var shadowWouldRemove = 0
    val upsert = AvailabilityUpsertCounters()

    fun record(
        agencyId: Long,
        year: Int,
        result: AvailabilitySyncResult,
    ) {
        successes++
        upsert.addAll(result.upsert)
        if (result.unmappedNonEmpty) unmapped.add("$agencyId/$year")
        when (val outcome = result.outcome) {
            ReconcileOutcome.Empty -> emptyByYear.getOrPut(year) { mutableListOf() }.add(agencyId)
            is ReconcileOutcome.Breaker ->
                breakerTripped.add("$agencyId/$year: ${outcome.toRemove} of ${outcome.inScope} (cap ${outcome.cap})")
            is ReconcileOutcome.Removed -> removed += outcome.removed
            is ReconcileOutcome.Shadow -> shadowWouldRemove += outcome.wouldRemove
            ReconcileOutcome.NoPresentYachts, ReconcileOutcome.NothingAbsent -> Unit
        }
    }

    fun toLogLine(): String {
        val empty =
            emptyByYear.entries.joinToString(", ") { (year, ids) ->
                if (year == currentYear) "$year=${ids.size} $ids" else "$year=${ids.size}"
            }
        return "$partner availability run summary: agencies=$agencies calls=$calls failures=$failures " +
            "empty=[$empty] unmappedNonEmpty=$unmapped removed=$removed shadowWouldRemove=$shadowWouldRemove " +
            "breakerTripped=$breakerTripped $upsert"
    }
}
