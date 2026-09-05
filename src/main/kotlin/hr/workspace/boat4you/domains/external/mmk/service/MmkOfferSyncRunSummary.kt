package hr.workspace.boat4you.domains.external.mmk.service

/**
 * Per-offer outcomes of one [MmkYachtOfferSyncService.syncOffersForAgency] call, counted
 * instead of logged. "Removed" does not exist here by design: the MMK agency sweep has been
 * upsert-only since 20.7.2026 (Shamane/6777) — occupancy is owned by the availability sync.
 */
class MmkOfferUpsertCounters {
    var upserted = 0
    var skippedMissingLocation = 0
    var skippedMissingYachtMapping = 0
    var skippedLocked = 0

    fun addAll(other: MmkOfferUpsertCounters) {
        upserted += other.upserted
        skippedMissingLocation += other.skippedMissingLocation
        skippedMissingYachtMapping += other.skippedMissingYachtMapping
        skippedLocked += other.skippedLocked
    }

    override fun toString(): String =
        "upserted=$upserted removed=0 skippedMissingLocation=$skippedMissingLocation " +
            "skippedMissingYachtMapping=$skippedMissingYachtMapping skippedLocked=$skippedLocked"
}

/**
 * Result of one agency's offer sweep.
 * @param calls /offers requests made (one per reservation-option group × date window).
 * @param failures calls whose response could not be fetched or upserted.
 */
data class MmkAgencyOfferSyncResult(
    val calls: Int,
    val failures: Int,
    val upsert: MmkOfferUpsertCounters,
)

/**
 * Accumulates one MMK offer run (nightly full horizon or the near-term 12-week refresh) into a
 * single INFO line. Mario 5.9.2026: weekly searches no longer warm partners live, so this run
 * is the freshness engine for the bookable window and its summary is what ops reads.
 */
class MmkOfferSyncRunSummary(
    private val label: String,
) {
    var agencies = 0
    var calls = 0
    var failures = 0

    /** Agency batches whose 15-min `allOf` timeout fired (their unfinished agencies are not counted). */
    var timedOutBatches = 0
    val upsert = MmkOfferUpsertCounters()

    fun record(result: MmkAgencyOfferSyncResult) {
        calls += result.calls
        failures += result.failures
        upsert.addAll(result.upsert)
    }

    fun toLogLine(): String = "MMK offer sync ($label): agencies=$agencies calls=$calls failures=$failures timedOutBatches=$timedOutBatches $upsert"
}
