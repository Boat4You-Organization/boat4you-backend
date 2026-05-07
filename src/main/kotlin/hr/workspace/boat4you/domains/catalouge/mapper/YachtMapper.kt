package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.common.services.parseYachtSearchViewLocationName
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsResponse
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtResponse
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.MeasurementUnitDto
import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchResponseDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetail
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtView
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtSearchSelectResult
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslation
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.toDto
import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import kotlin.collections.filter
import kotlin.collections.sortedBy

@Component
class YachtMapper(
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
    private val yachtExtrasMapper: YachtExtrasMapper,
) {
    private fun isAdminUser(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication.authorities.any { it.authority == "SYSTEM_ADMIN" }
    }

    fun toDto(
        result: YachtSearchSelectResult,
        currency: CurrencyEnum?,
        language: LanguageEnum,
        isOption: Boolean,
        amenityKeys: List<String>? = null,
        optionExpiresAt: java.time.LocalDateTime? = null,
    ): YachtSearchResponseDto {
        val yachtLocation = parseYachtSearchViewLocationName(result.locationFullName)
        // One-way charter: surface drop-off as separate DTO only when
        // it differs from the pickup. View encodes both as `id-name-cc`
        // strings so straight equality is enough to detect "same marina".
        val yachtLocationTo =
            result.locationToFullName
                ?.takeIf { it != result.locationFullName }
                ?.let { parseYachtSearchViewLocationName(it) }

        // Resolve the raw Int offer_status (from the view) into the enum for UI.
        val status =
            result.offerStatus?.let { raw ->
                hr.workspace.boat4you.domains.catalouge.enums.OfferStatus.entries.firstOrNull { it.value == raw }
            }

        return YachtSearchResponseDto(
            id = result.id,
            slug = SlugUtils.toSlugWithId(result.manufacturerName, result.modelName, result.yachtName, result.id),
            name = result.yachtName,
            location = yachtLocation,
            locationTo = yachtLocationTo,
            totalLocations = result.sumLocations?.toInt(),
            charterType = result.charterType,
            vesselType = result.vesselType,
            buildYear = result.buildYear,
            maxPersons = result.maxPersons,
            cabins = result.cabins,
            length = result.length,
            lengthInfo = MeasurementUnitDto.toDto(result.length, language),
            clientPriceEur = result.clientPrice,
            clientPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    result.clientPrice,
                    currency,
                ),
            listPriceEur = result.listPrice,
            listPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    result.listPrice,
                    currency,
                ),
            numberOfDays = result.numberOfDays,
            modelName = result.modelName,
            mainImageId = result.mainImage,
            isOption = isOption,
            offerStatus = status,
            agencyName = if (isAdminUser()) result.agencyName else null,
            // Commission is broker-only data — never leak to customer UI.
            // Sourced from offer.broker_commission (partner's per-offer
            // figure), NOT offer.agency_commission (our client discount).
            agencyCommissionEur = if (isAdminUser()) result.brokerCommission else null,
            amenityKeys = amenityKeys?.takeIf { it.isNotEmpty() },
            offerDateFrom = result.offerDateFrom,
            offerDateTo = result.offerDateTo,
            optionExpiresAt = optionExpiresAt,
            custom = result.entryType == hr.workspace.boat4you.domains.catalouge.enums.EntryType.CUSTOM,
        )
    }

    fun toDetailsDto(
        result: Yacht,
        offerDtos: List<OfferDto>?,
        yachtExtras: List<YachtExtra>,
        currency: CurrencyEnum?,
        language: LanguageEnum,
    ): YachtDetailsDto {
        val model = result.model
        val manufacturer = model?.manufacturer

        val description =
            result.yachtTranslations
                .filter { it.language?.locale == language.locale && it.type == TranslationType.DESCRIPTION }
                .map { it.value }
                .firstOrNull()

        val highlights =
            result.yachtTranslations
                .filter { it.language?.locale == language.locale && it.type == TranslationType.HIGHLIGHTS }
                .map { it.value }
                .firstOrNull()

        val location =
            if (result.entryType == EntryType.CUSTOM) {
                val customDetails = result.customYachtDetails.firstOrNull()
                val country = customDetails?.country!!
                LocationDto(id = "C-${country.id}", name = country.name, countryCode = country.code2)
            } else {
                // Fallback: if yacht has no location_id (OSH/MMK legacy — DESSUS, ADRIATIC PEARL,
                // CATWALK, MADAME EL GRANDE...), pull the pick-up location from the first offer so
                // the detail page matches the listing (which filters by offer.location_from anyway).
                result.location?.toDto() ?: offerDtos?.firstOrNull()?.locationFrom
            }

        val extras =
            yachtExtras
                .filter { it.shouldDisplay() }
                .map { yachtExtrasMapper.toDto(it, currency) }

        return YachtDetailsDto(
            id = result.id!!,
            name = result.name!!,
            SlugUtils.toSlugWithId(manufacturer?.name, model?.name, result.name, result.id!!),
            buildYear = result.buildYear,
            maxPersons = result.maxPersons,
            cabins = result.cabins,
            wc = result.wc,
            berths = result.berths,
            enginePower = result.enginePower,
            fuelTank = result.fuelTank,
            waterTank = result.waterTank,
            beam = result.beam,
            beamInfo = MeasurementUnitDto.toDto(result.beam, language),
            mainSailType = result.mainsailType,
            length = result.length,
            lengthInfo = MeasurementUnitDto.toDto(result.length, language),
            model = result.model?.name ?: "",
            offers = offerDtos,
            agency = if (isAdminUser()) result.agency?.toDto() else null,
            location = location,
            yachtImages =
                result.yachtImages
                    .filter { !it.url.isNullOrEmpty() }
                    .map { it.toDto() }.sortedBy {
                    it.mainImage
                    it.position
                },
            // Emit every yacht_equipment row, not just rows that matched a
            // predefined Equipment record. Partner sync (MMK / NauSys) ships
            // ~25-30 equipment items per yacht but our Equipment table only
            // covers a subset — historically the filter dropped everything
            // unmatched, leaving the public Amenities tab with 6-8 items vs
            // a competitor's 25+. Keep distinctBy keyed on equipmentId for
            // matched rows and on name for unmatched rows so Hibernate's
            // duplicate yacht_equipment writes still collapse cleanly.
            amenities = result.yachtEquipments
                .distinctBy { it.equipmentId ?: ("name:" + (it.name ?: "")) }
                .map { it.toDto() },
            services = extras,
            description = description,
            highlights = highlights,
            custom = result.entryType == EntryType.CUSTOM,
            customDetails =
                if (result.entryType == EntryType.CUSTOM) {
                    toCustomYachtDetailsDto(result.customYachtDetails.firstOrNull(), currency)
                } else {
                    null
                },
            securityDeposit = result.deposit,
            insuredSecurityDeposit = result.insuredDeposit,
            depositCurrency = result.depositCurrency,
            crewNumber = result.crewNumber,
            defaultCheckin = result.defaultCheckin,
            defaultCheckout = result.defaultCheckout,
            charterType = result.yachtCharterTypes.map { it.type!! }.toSet(),
            inquireOnly = result.isInquireOnly(),
            vesselType = result.vesselType ?: VesselType.OTHER,
        )
    }

    fun toCustomYachtDetailsDto(
        customYachtDetail: CustomYachtDetail?,
        currency: CurrencyEnum?,
    ): CustomYachtDetailsDto? {
        if (customYachtDetail == null) return null
        return CustomYachtDetailsDto(
            lowPrice = customYachtDetail.lowPrice!!,
            priceDescription = customYachtDetail.priceDescription,
            videoUrl = customYachtDetail.videoUrl,
            hasBrochure = !customYachtDetail.pdfUrl.isNullOrBlank(),
            lowPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    customYachtDetail.lowPrice,
                    currency,
                ),
            amenitiesText = customYachtDetail.amenitiesText,
            toysText = customYachtDetail.toysText,
            engineText = customYachtDetail.engineText,
        )
    }

    fun toDto(customYachtView: CustomYachtView): CustomYachtResponse {
        return CustomYachtResponse(
            id = customYachtView.id!!,
            name = customYachtView.name!!,
            modelName = customYachtView.modelName,
            countryId = customYachtView.countryKey!!,
            countryName = customYachtView.countryName!!,
            countryCode = customYachtView.countryCode!!,
            lowPrice = customYachtView.lowPrice!!,
            slug =
                SlugUtils.toSlugWithId(
                    customYachtView.manufacturerName,
                    customYachtView.modelName,
                    customYachtView.name,
                    customYachtView.id!!,
                ),
        )
    }

    fun toCustomYachtDetailsResponse(
        yacht: Yacht,
        customYachtDetail: CustomYachtDetail,
        translations: List<YachtTranslation>,
    ): CustomYachtDetailsResponse {
        val translationsGroups = translations.groupBy { it.type }
        val descriptions = translationsGroups.getOrDefault(TranslationType.DESCRIPTION, emptyList())
        return CustomYachtDetailsResponse(
            id = yacht.id!!,
            name = yacht.name!!,
            manufacturerId = yacht.model?.manufacturer?.id,
            modelId = yacht.model?.id,
            buildYear = yacht.buildYear,
            launchYear = yacht.launchYear,
            enginePower = yacht.enginePower,
            length = yacht.length,
            draught = yacht.draught,
            beam = yacht.beam,
            waterTank = yacht.waterTank,
            fuelTank = yacht.fuelTank,
            cabins = yacht.cabins,
            berths = yacht.berths,
            maxPersons = yacht.maxPersons,
            crewNumber = yacht.crewNumber,
            defaultCheckin = yacht.defaultCheckin,
            defaultCheckout = yacht.defaultCheckout,
            vesselType = yacht.vesselType,
            countryId = customYachtDetail.countryKey!!,
            // Re-emit yacht.location.id with the `l-` marina prefix so the
            // admin form can re-select the same marina on edit. Falls
            // through as null for yachts created before the marina selector
            // existed — the form treats null as "force the user to pick".
            locationId = yacht.location?.id?.let { "l-$it" },
            lowPrice = customYachtDetail.lowPrice!!,
            videoUrl = customYachtDetail.videoUrl,
            descriptions = descriptions.associateBy({ it.language!!.locale!! }, { it.value!! }),
            equipment = yacht.yachtEquipments.map { it.toDto() }.toSet(),
            yachtImages =
                yacht.yachtImages.map { it.toDto() }.sortedBy {
                    it.mainImage
                    it.position
                },
            hasBrochure = if (customYachtDetail.pdfUrl.isNullOrBlank()) false else true,
            priceDescription = customYachtDetail.priceDescription,
            amenitiesText = customYachtDetail.amenitiesText,
            toysText = customYachtDetail.toysText,
            engineText = customYachtDetail.engineText,
            slug = SlugUtils.toSlugWithId(yacht.model?.manufacturer?.name, yacht.model?.name, yacht.name, yacht.id!!),
        )
    }
}
