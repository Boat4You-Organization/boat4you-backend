package hr.workspace.boat4you.domains.external.service

/**
 * What one [ExternalAvailabilityReconcileService.reconcileAbsent] pass did for an (agency, year).
 * Returned instead of logging per call so the availability integration can fold ~2,000 calls
 * per run into ONE summary line (the old per-call "Skip absent-reconcile … ZERO reservations"
 * WARN fired ~4,650×/day for small agencies and future years — plausible no-data, not a fault).
 */
sealed interface ReconcileOutcome {
    /** EMPTY-guard: partner returned zero reservations → treated as no-data, nothing touched. */
    data object Empty : ReconcileOutcome

    /** PER-YACHT-PRESENT guard: none of the agency's yachts appeared in the response. */
    data object NoPresentYachts : ReconcileOutcome

    /** Every in-scope row is still returned by the partner — nothing to remove. */
    data object NothingAbsent : ReconcileOutcome

    /** CIRCUIT BREAKER: absent fraction over the cap → likely truncated response, nothing removed. */
    data class Breaker(val toRemove: Int, val inScope: Int, val cap: Int) : ReconcileOutcome

    /** SHADOW MODE: would have removed [wouldRemove] rows; nothing deleted. */
    data class Shadow(val wouldRemove: Int, val inScope: Int) : ReconcileOutcome

    data class Removed(val removed: Int, val inScope: Int) : ReconcileOutcome
}
