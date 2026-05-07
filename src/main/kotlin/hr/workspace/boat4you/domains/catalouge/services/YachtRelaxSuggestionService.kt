package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.RelaxSuggestionDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Picks the active filter that — if dropped — would surface the most
 * additional boats. Powers the V2 "AI hint" strip per design handoff
 * §"AI hint strip".
 *
 * First iteration covers the four high-impact range filters (year,
 * length, price ceiling, cabins). For each, run two queries:
 *   1. baseline count with all current filters
 *   2. count with just that filter removed
 * Delta = (#2 − #1). Whichever filter has the biggest positive delta
 * AND ≥ [SURFACE_THRESHOLD] (handoff: 20) wins; otherwise null.
 *
 * SQL is hand-rolled native (vs JPA criteria) because the rest of
 * `yacht_search_view` already has a hot custom path through
 * `YachtRepository`; replicating its joins through criteria here
 * would be ~200 lines for marginal gain. We deliberately keep the
 * filter set narrow — surfacing too many "drop this filter" tips
 * trains users to ignore them.
 */
@Service
class YachtRelaxSuggestionService(
    private val entityManager: EntityManager,
    private val locationRepository: LocationRepository,
) {
    @Transactional(readOnly = true)
    fun suggest(filters: ActiveFilters): RelaxSuggestionDto? {
        if (!filters.hasAnyRelaxable()) return null

        // Resolve `did` (country / region / marina) into the matching marina
        // IDs once — avoids re-running the marina lookup in every WHERE build.
        val resolved = filters.copy(marinaIds = resolveMarinas(filters.locationIds))
        val baseline = countWith(resolved)

        val candidates = mutableListOf<RelaxSuggestionDto>()

        if (resolved.minBuildYear != null || resolved.maxBuildYear != null) {
            val withoutYear = resolved.copy(minBuildYear = null, maxBuildYear = null)
            val delta = countWith(withoutYear) - baseline
            val label = formatYearLabel(resolved.minBuildYear, resolved.maxBuildYear)
            candidates += RelaxSuggestionDto(listOf("minBuildYear", "maxBuildYear"), label, delta)
        }
        if (resolved.minLength != null || resolved.maxLength != null) {
            val withoutLen = resolved.copy(minLength = null, maxLength = null)
            val delta = countWith(withoutLen) - baseline
            val label = formatLengthLabel(resolved.minLength, resolved.maxLength)
            candidates += RelaxSuggestionDto(listOf("minLength", "maxLength"), label, delta)
        }
        if (resolved.maxPrice != null) {
            val withoutPriceCap = resolved.copy(maxPrice = null)
            val delta = countWith(withoutPriceCap) - baseline
            val label = "Price ≤ €${resolved.maxPrice}"
            candidates += RelaxSuggestionDto(listOf("maxPrice"), label, delta)
        }
        if (resolved.minCabins != null || resolved.maxCabins != null) {
            val withoutCab = resolved.copy(minCabins = null, maxCabins = null)
            val delta = countWith(withoutCab) - baseline
            val label = formatCabinsLabel(resolved.minCabins, resolved.maxCabins)
            candidates += RelaxSuggestionDto(listOf("minCabins", "maxCabins"), label, delta)
        }

        return candidates
            .filter { it.delta >= SURFACE_THRESHOLD }
            .maxByOrNull { it.delta }
    }

    private fun countWith(filters: ActiveFilters): Long {
        val (where, params) = buildWhereClause(filters)
        // COUNT(DISTINCT id) — yacht_search_view has one row per offer; a
        // yacht with multiple candidate offers in the date-flex window would
        // otherwise be counted multiple times and the delta would explode.
        @Suppress("SqlSourceToSinkFlow")
        val sql = "SELECT COUNT(DISTINCT id) FROM yacht_search_view $where"
        val q = entityManager.createNativeQuery(sql)
        params.forEach { (k, v) -> q.setParameter(k, v) }
        return (q.singleResult as Number).toLong()
    }

    private fun buildWhereClause(filters: ActiveFilters): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        // Always-on filters (mirror `YachtQueryingService` so relax delta
        // matches the listing's "Boats available" baseline).
        clauses += "offer_status <> 4"
        when {
            filters.marinaIds == null -> {}
            filters.marinaIds.isEmpty() -> clauses += "FALSE"
            else -> {
                clauses += "(location_from IN (:marinaIds) OR location_to IN (:marinaIds))"
                params["marinaIds"] = filters.marinaIds
            }
        }
        filters.startDate?.let {
            clauses += "date_from BETWEEN :startMinusFlex AND :startPlusFlex"
            params["startMinusFlex"] = it.minusDays(DATE_FLEX_DAYS)
            params["startPlusFlex"] = it.plusDays(DATE_FLEX_DAYS)
        } ?: filters.endDate?.let {
            clauses += "date_to BETWEEN :endMinusFlex AND :endPlusFlex"
            params["endMinusFlex"] = it.minusDays(DATE_FLEX_DAYS)
            params["endPlusFlex"] = it.plusDays(DATE_FLEX_DAYS)
        }
        if (!filters.vesselTypes.isNullOrEmpty()) {
            clauses += "vessel_type IN (:vesselTypeOrdinals)"
            params["vesselTypeOrdinals"] = filters.vesselTypes.map { it.ordinal }
        }
        if (!filters.charterTypes.isNullOrEmpty()) {
            clauses += "charter_type IN (:charterTypeOrdinals)"
            params["charterTypeOrdinals"] = filters.charterTypes.map { it.ordinal }
        }
        // Relaxable dimensions (subject to `copy(... = null)` in [suggest]).
        filters.minBuildYear?.let { clauses += "build_year >= :minBuildYear"; params["minBuildYear"] = it }
        filters.maxBuildYear?.let { clauses += "build_year <= :maxBuildYear"; params["maxBuildYear"] = it }
        filters.minLength?.let { clauses += "length >= :minLength"; params["minLength"] = it }
        filters.maxLength?.let { clauses += "length <= :maxLength"; params["maxLength"] = it }
        // `maxPrice` arrives weekly from the slider but `client_price` in
        // yacht_search_view is per-day. Convert before the WHERE clause so
        // the relax-suggest delta matches the listing's filtered count.
        filters.maxPrice?.let { clauses += "client_price <= :maxPrice"; params["maxPrice"] = it / 7 }
        filters.minCabins?.let { clauses += "cabins >= :minCabins"; params["minCabins"] = it }
        filters.maxCabins?.let { clauses += "cabins <= :maxCabins"; params["maxCabins"] = it }
        return "WHERE ${clauses.joinToString(" AND ")}" to params
    }

    /** Resolve `did=c-54 / r-12 / l-9001` strings into the matching marina
     *  IDs (mirrors `YachtQueryingService.getMarinas` / `YachtDistributionService.resolveMarinas`).
     *  `null` when no destination filter is active. */
    private fun resolveMarinas(locationIds: List<String>?): List<Long>? {
        if (locationIds.isNullOrEmpty()) return null
        return locationIds
            .flatMap { id ->
                val type = when (id.firstOrNull()) {
                    'r' -> LocationType.REGION
                    'c' -> LocationType.COUNTRY
                    'l' -> LocationType.MARINA
                    else -> return@flatMap emptyList()
                }
                val numeric = id.substring(2).toIntOrNull() ?: return@flatMap emptyList()
                when (type) {
                    LocationType.MARINA -> locationRepository.findById(numeric.toLong())
                        .map { listOfNotNull(it.id) }.orElse(emptyList())
                    LocationType.COUNTRY -> locationRepository.findMarinasByCountryId(numeric).mapNotNull { it.id }
                    LocationType.REGION -> locationRepository.findMarinasByRegionId(numeric).mapNotNull { it.id }
                }
            }
            .distinct()
    }

    private fun formatYearLabel(min: Int?, max: Int?) = when {
        min != null && max != null && min == max -> "Year = $min"
        min != null && max != null -> "Year $min – $max"
        min != null -> "Year ≥ $min"
        max != null -> "Year ≤ $max"
        else -> "Year"
    }

    private fun formatLengthLabel(min: Int?, max: Int?) = when {
        min != null && max != null -> "Length $min – $max m"
        min != null -> "Length ≥ $min m"
        max != null -> "Length ≤ $max m"
        else -> "Length"
    }

    private fun formatCabinsLabel(min: Int?, max: Int?) = when {
        min != null && max != null && min == max -> "$min cabins"
        min != null -> "${min}+ cabins"
        max != null -> "≤ $max cabins"
        else -> "Cabins"
    }

    /** Compact data class collecting the filter dimensions the relax
     *  suggestion considers. The first group (locationIds / dates / vessel
     *  / charter) are *always-on* — they're held constant across every
     *  candidate-with-X-removed query, so the listing's destination /
     *  date / type context flows through. The bottom group is *relaxable* —
     *  each branch in [suggest] tests dropping one dimension. `marinaIds`
     *  is filled by [resolveMarinas] inside [suggest]. */
    data class ActiveFilters(
        val locationIds: List<String>? = null,
        val startDate: LocalDate? = null,
        val endDate: LocalDate? = null,
        val vesselTypes: List<VesselType>? = null,
        val charterTypes: List<CharterType>? = null,
        val minBuildYear: Int? = null,
        val maxBuildYear: Int? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val maxPrice: Int? = null,
        val minCabins: Int? = null,
        val maxCabins: Int? = null,
        /** Resolved marina IDs from `locationIds` — populated internally,
         *  never set by callers. */
        val marinaIds: List<Long>? = null,
    ) {
        fun hasAnyRelaxable(): Boolean =
            listOf(minBuildYear, maxBuildYear, minLength, maxLength, maxPrice, minCabins, maxCabins)
                .any { it != null }
    }

    companion object {
        private const val SURFACE_THRESHOLD = 20

        // Match `YachtQueryingService.DATE_FLEX_DAYS` / distribution service.
        private const val DATE_FLEX_DAYS = 3L
    }
}
