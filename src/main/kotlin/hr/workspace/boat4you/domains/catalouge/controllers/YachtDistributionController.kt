package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.RelaxSuggestionDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtDistributionDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.services.YachtDistributionService
import hr.workspace.boat4you.domains.catalouge.services.YachtRelaxSuggestionService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Read-only endpoints powering the V2 search filter sidebar's
 * histograms, per-option counts, and AI hint strip. Sits beside
 * `YachtController` (same `/public/yachts` mount, different
 * sub-paths) so the frontend hooks (`useFilterDistribution`,
 * `useRelaxSuggestion`) only need one base URL.
 *
 * Both endpoints accept the same param shape as `GET /public/yachts`
 * — we forward the relevant dimensions into
 * [YachtRelaxSuggestionService] for the relax delta calc; distribution
 * is currently global (Phase 2.0) and ignores the params, with
 * Phase 3 wiring up filtered aggregates.
 */
@RestController
@RequestMapping("/public/yachts")
class YachtDistributionController(
    private val distributionService: YachtDistributionService,
    private val relaxService: YachtRelaxSuggestionService,
) {
    @GetMapping("/distribution")
    fun getDistribution(
        @RequestParam(name = "did", required = false) locationIds: List<String>?,
        @RequestParam(name = "startDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate?,
        @RequestParam(name = "endDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate?,
        @RequestParam(name = "boatTypes", required = false) vesselTypes: List<VesselType>?,
        @RequestParam(name = "charterType", required = false) charterTypes: List<CharterType>?,
        @RequestParam(name = "mainSailType", required = false) mainsailTypes: List<SailTypeEnum>?,
        @RequestParam(name = "minBuildYear", required = false) minBuildYear: Short?,
        @RequestParam(name = "maxBuildYear", required = false) maxBuildYear: Short?,
        @RequestParam(name = "minPersons", required = false) minPersons: Short?,
        @RequestParam(name = "maxPersons", required = false) maxPersons: Short?,
        @RequestParam(name = "minCabins", required = false) minCabins: Short?,
        @RequestParam(name = "maxCabins", required = false) maxCabins: Short?,
        @RequestParam(name = "minBerths", required = false) minBerths: Short?,
        @RequestParam(name = "maxBerths", required = false) maxBerths: Short?,
        @RequestParam(name = "minLength", required = false) minLength: BigDecimal?,
        @RequestParam(name = "maxLength", required = false) maxLength: BigDecimal?,
        @RequestParam(name = "minWc", required = false) minWc: Short?,
        @RequestParam(name = "maxWc", required = false) maxWc: Short?,
        @RequestParam(name = "minEnginePower", required = false) minEnginePower: Short?,
        @RequestParam(name = "maxEnginePower", required = false) maxEnginePower: Short?,
        @RequestParam(name = "minPrice", required = false) minPriceWeekly: BigDecimal?,
        @RequestParam(name = "maxPrice", required = false) maxPriceWeekly: BigDecimal?,
        @RequestParam(name = "mfid", required = false) manufacturerIds: List<Long>?,
        @RequestParam(name = "mid", required = false) modelIds: List<Long>?,
    ): ResponseEntity<YachtDistributionDto> {
        return ResponseEntity.ok(
            distributionService.getDistribution(
                locationIds = locationIds,
                startDate = startDate,
                endDate = endDate,
                vesselTypes = vesselTypes,
                charterTypes = charterTypes,
                mainsailTypes = mainsailTypes,
                minBuildYear = minBuildYear, maxBuildYear = maxBuildYear,
                minPersons = minPersons, maxPersons = maxPersons,
                minCabins = if (minCabins == 0.toShort()) null else minCabins,
                maxCabins = if (maxCabins == 0.toShort()) null else maxCabins,
                minBerths = minBerths, maxBerths = maxBerths,
                minLength = minLength, maxLength = maxLength,
                minWc = minWc, maxWc = maxWc,
                minEnginePower = minEnginePower, maxEnginePower = maxEnginePower,
                minPriceWeekly = minPriceWeekly, maxPriceWeekly = maxPriceWeekly,
                manufacturerIds = manufacturerIds, modelIds = modelIds,
            ),
        )
    }

    @GetMapping("/relax-suggest")
    fun relaxSuggest(
        @RequestParam(name = "did", required = false) locationIds: List<String>?,
        @RequestParam(name = "startDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate?,
        @RequestParam(name = "endDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate?,
        @RequestParam(name = "boatTypes", required = false) vesselTypes: List<VesselType>?,
        @RequestParam(name = "charterType", required = false) charterTypes: List<CharterType>?,
        @RequestParam(required = false) minBuildYear: Int?,
        @RequestParam(required = false) maxBuildYear: Int?,
        @RequestParam(required = false) minLength: Int?,
        @RequestParam(required = false) maxLength: Int?,
        @RequestParam(required = false) maxPrice: Int?,
        @RequestParam(required = false) minCabins: Int?,
        @RequestParam(required = false) maxCabins: Int?,
    ): ResponseEntity<RelaxSuggestionDto> {
        val suggestion =
            relaxService.suggest(
                YachtRelaxSuggestionService.ActiveFilters(
                    locationIds = locationIds,
                    startDate = startDate,
                    endDate = endDate,
                    vesselTypes = vesselTypes,
                    charterTypes = charterTypes,
                    minBuildYear = minBuildYear,
                    maxBuildYear = maxBuildYear,
                    minLength = minLength,
                    maxLength = maxLength,
                    maxPrice = maxPrice,
                    minCabins = minCabins,
                    maxCabins = maxCabins,
                ),
            )
        // 204 when there's no signal worth surfacing — frontend hides
        // the hint strip on null body.
        return suggestion?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }
}
