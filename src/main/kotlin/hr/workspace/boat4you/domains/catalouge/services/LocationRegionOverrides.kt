package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Region
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import kotlin.jvm.optionals.getOrNull

/**
 * Region pins for locations the partner catalogues mis-classify.
 *
 * Both `locationsSync` paths (NauSys + MMK) do `location.regions.add(...)` and
 * NEVER remove, so a wrong partner region keeps re-accumulating: every manual
 * data cleanup is reverted on the next catalogue sync. The textbook case is
 * **Marina Frapa, Rogoznica** (`location.id = 2029`), which the partners report
 * under Dubrovnik / Kornati / Šibenik — it must only ever appear under Split.
 * Cleaning the data alone was undone again and again (hence the recurrence).
 *
 * For a pinned location we force its region set to EXACTLY the curated ids on
 * every sync, ignoring whatever the partner says. That makes the correction
 * permanent without per-deploy babysitting. The matching one-time data cleanup
 * lives in migration `V9_18__frapa_rogoznica_split_only.sql`. (Mario 9.6.2026)
 */
object LocationRegionOverrides {
    /** locationId -> the ONLY region ids this location may belong to. */
    val ALLOWED: Map<Long, Set<Long>> =
        mapOf(
            2029L to setOf(5L), // Marina Frapa, Rogoznica -> Split region (5) only
        )
}

/**
 * Attach the partner-derived [candidates] to [location] — unless the location is
 * pinned in [LocationRegionOverrides], in which case its regions are forced to
 * exactly the curated set and the partner classification is discarded.
 *
 * Idempotent: a pinned location processed once per partner mapping (Frapa has
 * three) ends each pass with the same curated set.
 */
fun applyLocationRegions(
    location: Location,
    candidates: List<Region>,
    regionRepository: RegionRepository,
) {
    val pinnedIds = location.id?.let { LocationRegionOverrides.ALLOWED[it] }
    if (pinnedIds != null) {
        val pinnedRegions = pinnedIds.mapNotNull { regionRepository.findById(it).getOrNull() }
        location.regions.clear()
        location.regions.addAll(pinnedRegions)
    } else {
        location.regions.addAll(candidates)
    }
}
