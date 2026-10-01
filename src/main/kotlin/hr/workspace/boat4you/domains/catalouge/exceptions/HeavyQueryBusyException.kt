package hr.workspace.boat4you.domains.catalouge.exceptions

import hr.workspace.boat4you.domains.catalouge.services.HeavyQuery

/**
 * A heavy public read (facet distribution, search listing) was shed instead of run: either its
 * concurrency gate in `HeavyQueryGuard` was full ([Reason.SATURATED]) or the query ran past its
 * statement / transaction timeout ([Reason.TIMED_OUT]). Both are transient capacity signals, not
 * client mistakes. Maps to 503 + `Retry-After` (ApiErrorHandler).
 *
 * 1.10.2026 (Codex audit F2): 22 Hikari connections were held 60-120 s by
 * `getDistribution` alone and boat pages / standard-offers waited 20 s for a connection and
 * failed; shedding the facet/listing burst is what keeps those detail paths served.
 */
class HeavyQueryBusyException(
    val query: HeavyQuery,
    val reason: Reason,
    cause: Throwable? = null,
) : RuntimeException("Heavy query ${query.name} shed: ${reason.name}", cause) {
    enum class Reason { SATURATED, TIMED_OUT }
}
