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
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
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
                minPrice = minPrice,
                maxPrice = maxPrice,
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
                language = language,
            )

        if (startDate != null && endDate != null) {
            if (ChronoUnit.DAYS.between(startDate, endDate) < 3L || ChronoUnit.DAYS.between(startDate, endDate) > 28L) {
                return ResponseEntity.badRequest().build()
            }
        }

        if (startDate != null && endDate != null &&
            ChronoUnit.DAYS.between(startDate, endDate) != 7L
        ) {
            if (locations != null) {
                externalSyncService.syncYachtOffers(startDate, endDate, locations)
            }
        }

        return ResponseEntity.ok(
            PagedModel(
                yachtQueryingService.getYachts(
                    searchParams,
                    sortBy,
                    language,
                    page,
                    size,
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
        val yachtId = SlugUtils.idFromSlug(yachtSlug)
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
        val yachtId = SlugUtils.idFromSlug(yachtSlug)
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
        val yachtId = SlugUtils.idFromSlug(yachtSlug)
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
        val yachtId = SlugUtils.idFromSlug(yachtSlug)
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
