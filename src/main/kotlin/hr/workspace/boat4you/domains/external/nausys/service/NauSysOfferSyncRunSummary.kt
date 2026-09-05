package hr.workspace.boat4you.domains.external.nausys.service

/**
 * Counters for one NauSys offer-sync run (nightly full grid or the near-term 12-week
 * refresh), folded into a single INFO line by the job instead of per-group/per-interval
 * lines (Part 4 logging discipline). Mario 5.9.2026: search is served from the DB, so
 * these runs ARE the freshness engine and their one summary line is what ops reads.
 */
class NauSysOfferSyncRunSummary {
    var agencies = 0

    /** Agencies whose whole sync threw (outside the per-interval retry path). */
    var agencyFailures = 0

    /** freeYachts intervals attempted (one partner call each). */
    var intervals = 0

    /** Rows NauSys returned (= offers upserted, minus the few skipped for mapping gaps). */
    var offersReturned = 0

    /** FREE → pre-reserved / UNAVAILABLE flips made by the disappearance pass. */
    var disappeared = 0

    /** Intervals deferred to `nausys_offer_sync_retry` (429 / 5xx / parse failures). */
    var deferred = 0

    fun toLogLine(): String =
        "agencies=$agencies agencyFailures=$agencyFailures intervals=$intervals " +
            "offersReturned=$offersReturned disappeared=$disappeared deferred=$deferred"
}
