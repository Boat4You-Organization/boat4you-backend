package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal

data class YachtDetailsDto(
    val id: Long,
    val name: String,
    val slug: String,
    val buildYear: Short? = null,
    val maxPersons: Short? = null,
    val cabins: Short? = null,
    val wc: Short? = null,
    val berths: Short? = null,
    val enginePower: Short? = null,
    val fuelTank: Int? = null,
    val waterTank: Int? = null,
    val beam: BigDecimal? = null,
    val beamInfo: MeasurementUnitDto? = null,
    val mainSailType: SailTypeEnum? = null,
    val length: BigDecimal? = null,
    val lengthInfo: MeasurementUnitDto? = null,
    val model: String?,
    val agency: AgencyDto?,
    val location: LocationDto?,
    val yachtImages: List<YachtImageDto>?,
    val sysDescription: String? = null,
    val locations: List<LocationViewDto> = emptyList(),
    val custom: Boolean = false,
    val amenities: List<YachtEquipmentDto> = emptyList(),
    val services: List<YachtExtrasDto> = emptyList(),
    val offers: List<OfferDto>? = null,
    val modelName: String? = null,
    val manufacturerName: String? = null,
    val description: String? = null,
    val highlights: String? = null,
    val customDetails: CustomYachtDetailsDto?,
    val securityDeposit: BigDecimal? = null,
    val insuredSecurityDeposit: BigDecimal? = null,
    val depositCurrency: String? = null,
    val crewNumber: Short? = null,
    val defaultCheckin: String? = null,
    val defaultCheckout: String? = null,
    val charterType: Set<CharterType> = emptySet(),
    val inquireOnly: Boolean = false,
    val vesselType: VesselType,
)

data class CustomYachtDetailsDto(
    val lowPrice: BigDecimal,
    val lowPriceInfo: PriceInfoDto?,
    val priceDescription: String?,
    val videoUrl: String?,
    val hasBrochure: Boolean,
)
