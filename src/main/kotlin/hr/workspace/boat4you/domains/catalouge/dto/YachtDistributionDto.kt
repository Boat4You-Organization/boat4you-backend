package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal

/**
 * Aggregate distribution payload powering the V2 search-filter
 * sidebar (`/search`). Returned by `GET /public/yachts/distribution`
 * and consumed by the frontend `useFilterDistribution` hook.
 *
 * Histograms are bar-height arrays — caller normalises them in the UI
 * (each atom rescales relative to its own max). `byVesselType` /
 * `byCharterType` / `byMainsailType` / `byCabins` map enum → row
 * count, used to surface "live" counts next to checkbox / grid /
 * pill options.
 *
 * The first iteration (Phase 2.0) ignores caller-supplied filters and
 * computes against the full `yacht_search_view`. Phase 3 will plug
 * `YachtSearchParamObject` into the WHERE clause so the histograms
 * rescale per active filter combination.
 */
data class YachtDistributionDto(
    val priceHistogram: List<Long>,
    val priceMedian: BigDecimal?,
    /** Cheapest / most expensive offer in the filtered candidate set.
     *  Drives the dynamic min/max bounds of the price slider so handles
     *  span the actual range of prices the user can choose from (rather
     *  than a static 0–46k cap that hides expensive gulets / luxury yachts).
     *  `null` when the filtered set is empty. */
    val priceMin: BigDecimal?,
    val priceMax: BigDecimal?,
    val lengthHistogram: List<Long>,
    val engineHistogram: List<Long>,
    val byVesselType: Map<VesselType, Long>,
    val byCharterType: Map<CharterType, Long>,
    val byMainsailType: Map<SailTypeEnum, Long>,
    val byCabins: Map<Int, Long>,
    /** manufacturer.id → distinct yacht count under the **other** active
     *  filters (everything except the manufacturer filter itself). Drives
     *  the manufacturer dropdown's disabled-state: brands with 0 yachts in
     *  the current vessel-type / location / date / etc. context render
     *  greyed out so user can't pick e.g. Aicon while filtering catamarans. */
    val byManufacturer: Map<Long, Long>,
    /** model.id → distinct yacht count under the **other** active filters
     *  (everything except the model filter itself). Drives the model
     *  dropdown's disabled-state same way `byManufacturer` does. */
    val byModel: Map<Long, Long>,
    /** equipment.id → distinct yacht count (other filters applied). Amenities
     *  dropdown greys out entries missing from this map so e.g. "Air conditioning"
     *  is selectable only when at least one yacht in the current context has it. */
    val byAmenity: Map<Long, Long>,
)
