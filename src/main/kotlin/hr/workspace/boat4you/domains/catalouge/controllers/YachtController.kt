package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchResponseDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.services.OfferQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtTwinCanonicalService
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.security.core.context.SecurityContextHolder
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.Resource
import org.springframework.data.web.PagedModel
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Tag(name = "Yachts")
@RestController
@RequestMapping("/public/yachts")
class YachtController(
    private val yachtQueryingService: YachtQueryingService,
    private val offerQueryingService: OfferQueryingService,
    private val externalSyncService: ExternalSyncService,
    private val userRepository: UserRepository,
    private val yachtTwinCanonicalService: YachtTwinCanonicalService,
) {
    @Operation(description = "Fetch all yachts by search criteria")
    @GetMapping
    fun getYachts(
        @RequestParam(name = "did", required = false) locations: List<String>?,
        @RequestParam(name = "charterType", required = false) charterType: List<CharterType>?,
        @RequestParam(name = "vesselType", required = false) vesselType: List<VesselType>?,
        @RequestParam(name = "mfid", required = false) manufacturer: List<Long>?,
        @RequestParam(name = "mid", required = false) model: List<Long>?,
        @RequestParam(name = "mainSailType", required = false) mainSailType: List<SailTypeEnum>?,
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
        @RequestParam(name = "minPrice", required = false) minPrice: BigDecimal?,
        @RequestParam(name = "maxPrice", required = false) maxPrice: BigDecimal?,
        @RequestParam(name = "startDate", required = false) startDate: LocalDate?,
        @RequestParam(name = "endDate", required = false) endDate: LocalDate?,
        @RequestParam(name = "minWc", required = false) minWc: Short?,
        @RequestParam(name = "maxWc", required = false) maxWc: Short?,
        @RequestParam(name = "minEnginePower", required = false) minEnginePower: Short?,
        @RequestParam(name = "maxEnginePower", required = false) maxEnginePower: Short?,
        @RequestParam(name = "currency", required = false) curr: String?,
        @RequestParam(name = "amenities", required = false) amenities: List<Long>?,
        @RequestParam(name = "services", required = false) services: List<Long>?,
        @RequestParam(name = "sortBy", required = false) sortBy: String?,
        @RequestParam(name = "yid", required = false) yachtIds: List<Long>?,
        /**
         * Admin-only — filter by specific agency (charter company) ids. The
         * customer-facing search UI does not expose this param; it's used by
         * the admin "Create Reservation" wizard so the operator can narrow to
         * a known partner (e.g. Adriatic Sailing, Croatia Yachting).
         */
        @RequestParam(name = "agencyId", required = false) agencyIds: List<Long>?,
        /**
         * Admin-only "replacement flow" flag. When true, UNAVAILABLE /
         * RESERVED offers are included so the admin can pick a yacht the
         * agency already assigned to the same customer in the partner
         * system. See YachtSearchParamObject.includeUnavailable.
         */
        @RequestParam(name = "includeUnavailable", defaultValue = "false") includeUnavailable: Boolean,
        /**
         * Sitemap-only — 2-letter ISO country whitelist (e.g. BS,ES,FR).
         * Returns only yachts whose home location ends in one of these
         * codes. Used by /sitemap-yachts so Google indexes only countries
         * we actively promote.
         */
        @RequestParam(name = "countryCodes", required = false) countryCodes: List<String>?,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "10") size: Int,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<PagedModel<YachtSearchResponseDto>> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val language = LanguageEnum.getLanguage(lang, user)
        val currency = CurrencyEnum.getCurrency(curr, user)
        // Drives the yachtSearchListCache bypass: admins see agencyName +
        // agencyCommissionEur (YachtMapper.isAdminUser() gates them on this exact
        // SYSTEM_ADMIN authority), so their results must never be cached and served
        // to a customer. Computed with the SAME expression the mapper uses
        // (authentication.authorities), null-safe so anonymous public searches
        // (authentication may be absent) never NPE — absent/again non-admin → false.
        val isAdmin =
            SecurityContextHolder.getContext().authentication
                ?.authorities?.any { it.authority == "SYSTEM_ADMIN" } ?: false
        val searchParams =
            YachtSearchParamObject(
                locationIds = locations,
                charterTypes = charterType,
                vesselTypes = vesselType,
                manufacturers = manufacturer,
                models = model,
                mainSailTypes = mainSailType,
                minBuildYear = minBuildYear,
                maxBuildYear = maxBuildYear,
                minPersons = minPersons,
                maxPersons = maxPersons,
                minCabins = minCabins,
                maxCabins = maxCabins,
                minBerths = minBerths,
                maxBerths = maxBerths,
                minLength = minLength,
                maxLength = maxLength,
                // Price params arrive as weekly amounts (slider is labelled
                // "Price per week" and uses `priceMin`/`priceMax` from the
                // distribution endpoint, both already × 7). Divide back to
                // per-day so the WHERE clause on `client_price` (which is
                // per-day in `yacht_search_view`) matches the slider value.
                minPrice = minPrice?.divide(BigDecimal(7), 2, RoundingMode.HALF_UP),
                maxPrice = maxPrice?.divide(BigDecimal(7), 2, RoundingMode.HALF_UP),
                startDate = startDate,
                endDate = endDate,
                minWc = minWc,
                maxWc = maxWc,
                minEnginePower = minEnginePower,
                maxEnginePower = maxEnginePower,
                currency = currency,
                amenities = amenities,
                services = services,
                yachtIds = yachtIds,
                agencyIds = agencyIds,
                includeUnavailable = includeUnavailable,
                countryCodes = countryCodes,
                language = language,
            )

        if (startDate != null && endDate != null) {
            if (ChronoUnit.DAYS.between(startDate, endDate) < 3L || ChronoUnit.DAYS.between(startDate, endDate) > 28L) {
                return ResponseEntity.badRequest().build()
            }
        }

        // On-demand partner availability sync for ANY dated search with
        // locations (Deploy 4: the old `!= 7L` skip starved the most common
        // Sat-Sat query of fresh data). The 1h ServiceCallCacheService gate
        // inside syncYachtOffers (shouldCallYachtSearch) still throttles repeat
        // calls, and honesty no longer depends on this completing because
        // availability is resolved live against external_reservations at query
        // time, so an in-flight or cache-skipped sync never yields a false
        // "available for your exact week".
        if (startDate != null && endDate != null && locations != null) {
            externalSyncService.syncYachtOffers(startDate, endDate, locations)
        }

        // Replacement flow — admin toggled "Include unavailable yachts" in
        // the Create-Reservation wizard. Go through a native-SQL path that
        // UNIONs `offer` and `external_reservations` so yachts the partner
        // already sold (and therefore have NO offer row in our DB) still
        // surface. See YachtQueryingService.getYachtsForReplacement for the
        // rationale. Regular customer/admin search stays on the main view.
        if (includeUnavailable) {
            return ResponseEntity.ok(
                PagedModel(
                    yachtQueryingService.getYachtsForReplacement(
                        searchParams,
                        language,
                        page,
                        size,
                    ),
                ),
            )
        }

        return ResponseEntity.ok(
            PagedModel(
                yachtQueryingService.getYachts(
                    searchParams,
                    sortBy,
                    language,
                    page,
                    size,
                    isAdmin,
                ),
            ),
        )
    }

    @Operation(description = "Get yacht details by ID")
    @GetMapping("/{yachtSlug}")
    fun getYacht(
        @PathVariable yachtSlug: String,
        @RequestParam(name = "dateFrom", required = false) dateFrom: LocalDate?,
        @RequestParam(name = "dateTo", required = false) dateTo: LocalDate?,
        @RequestParam(name = "currency", required = false) curr: String?,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<YachtDetailsDto> {
        // Resolve cross-source duplicates to the canonical copy so the detail
        // page (and the slug it returns → calendar, price-calc, reservation)
        // serves the most complete / highest-margin twin. No-op when disabled.
        val yachtId = yachtTwinCanonicalService.resolve(SlugUtils.idFromSlug(yachtSlug))
        if (yachtId == null) {
            return ResponseEntity.notFound().build()
        }
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val language = LanguageEnum.getLanguage(lang, user)
        val currency = CurrencyEnum.getCurrency(curr, user)

        if (dateFrom != null && dateTo != null) {
            externalSyncService.syncYachtOffers(yachtId, dateFrom, dateTo)
        }

        return ResponseEntity
            .ok()
            .headers { it.set("Content-Language", language.locale) }
            .body(yachtQueryingService.getYacht(yachtId, dateFrom, dateTo, currency, language))
    }

    @Operation(description = "Get yacht availability for a specific month and year")
    @GetMapping("/{yachtSlug}/availability")
    fun getYachtAvailability(
        @PathVariable yachtSlug: String,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = true) year: Int,
    ): ResponseEntity<List<YachtAvailabilityDto>> {
        val yachtId = yachtTwinCanonicalService.resolve(SlugUtils.idFromSlug(yachtSlug))
        if (yachtId == null) {
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok(yachtQueryingService.getYachtAvailability(yachtId, month, year))
    }

    @Operation(description = "Get offers for a specific yacht")
    @GetMapping("/{yachtSlug}/standard-offers")
    fun getYachtOffers(
        @PathVariable yachtSlug: String,
        @RequestParam(name = "dateFrom", required = true) dateFrom: LocalDate,
        @RequestParam(name = "dateTo", required = true) dateTo: LocalDate,
        @RequestParam(name = "currency", required = false) curr: String?,
    ): ResponseEntity<List<OfferDto>> {
        val yachtId = yachtTwinCanonicalService.resolve(SlugUtils.idFromSlug(yachtSlug))
        if (yachtId == null) {
            return ResponseEntity.notFound().build()
        }
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val currency = CurrencyEnum.getCurrency(curr, user)

        return ResponseEntity.ok(offerQueryingService.getYachtStandardOffers(yachtId, dateFrom, dateTo, currency))
    }

    @Operation(description = "Get offers for a specific yacht by date range")
    @GetMapping("/{yachtSlug}/offers")
    fun getYachtOffersByDate(
        @PathVariable yachtSlug: String,
        @RequestParam(name = "dateFrom", required = true) dateFrom: LocalDate,
        @RequestParam(name = "dateTo", required = true) dateTo: LocalDate,
        @RequestParam(name = "currency", required = false) curr: String?,
    ): ResponseEntity<List<OfferDto>> {
        val yachtId = yachtTwinCanonicalService.resolve(SlugUtils.idFromSlug(yachtSlug))
        if (yachtId == null) {
            return ResponseEntity.notFound().build()
        }
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val currency = CurrencyEnum.getCurrency(curr, user)

        externalSyncService.syncYachtOffers(yachtId, dateFrom, dateTo)

        return ResponseEntity.ok(offerQueryingService.getYachtOffers(yachtId, dateFrom, dateTo, currency))
    }

    @Operation(description = "Get offers price with included extras for a specific yacht")
    @GetMapping("/{yachtSlug}/offer/{offerId}/calculate")
    fun getYachtOffersByDate(
        @PathVariable yachtSlug: String,
        @PathVariable offerId: Long,
        @RequestParam(name = "selectedExtras", required = false) selectedExtras: Set<String>?,
        @RequestParam(name = "currency", required = false) curr: String?,
    ): ResponseEntity<PriceCalcDto> {
        val yachtId = SlugUtils.idFromSlug(yachtSlug)
        if (yachtId == null) {
            return ResponseEntity.notFound().build()
        }
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val currency = CurrencyEnum.getCurrency(curr, user)

        val priceCalc =
            offerQueryingService.getPriceForOfferWithExtras(yachtId, offerId, currency, selectedExtras ?: emptySet())
        return ResponseEntity.ok(priceCalc)
    }

    @Operation(description = "Get brochure")
    @GetMapping("/{yachtSlug}/brochure")
    fun getYachtBrochure(
        @PathVariable yachtSlug: String,
    ): ResponseEntity<Resource> {
        val yachtId = SlugUtils.idFromSlug(yachtSlug)
        if (yachtId == null) {
            return ResponseEntity.notFound().build()
        }

        val resource = yachtQueryingService.getCustomBoatBrochure(yachtId)

        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_PDF
                setContentDispositionFormData("attachment", "brochure-$yachtSlug.pdf")
                cacheControl = "must-revalidate, post-check=0, pre-check=0"
            }

        return ResponseEntity
            .ok()
            .headers(headers)
            .body(resource)
    }
}
