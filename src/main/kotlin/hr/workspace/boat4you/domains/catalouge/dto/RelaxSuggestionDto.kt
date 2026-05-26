package hr.workspace.boat4you.domains.catalouge.dto

/**
 * Server-side suggestion of which active filter to drop to unlock
 * the most additional boats. Drives the V2 "AI hint" strip above
 * search results. Backend returns 204 No Content (or null body) when
 * no filter has a delta ≥ 20 — frontend hides the strip in that case.
 */
data class RelaxSuggestionDto(
    /** URL param keys to clear when the user clicks "Relax filter".
     *  Multiple keys when a single visible filter spans two params
     *  (e.g. minBuildYear + maxBuildYear). */
    val paramKeys: List<String>,
    /** Human-readable label for the strip ("Year ≥ 2018", "Length
     *  10–18 m"). */
    val label: String,
    /** Boats that would appear if the named filter were removed. */
    val delta: Long,
)
