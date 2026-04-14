package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal

data class YachtSearchResponseDto(
    val id: Long,
    val slug: String,
    val name: String?,
    val location: LocationDto? = null,
    val charterType: CharterType? = null,
    val vesselType: VesselType? = null,
    val buildYear: Short? = null,
    val maxPersons: Short? = null,
    val cabins: Short? = null,
    val length: BigDecimal? = null,
    val lengthInfo: MeasurementUnitDto? = null,
    val totalLocations: Int? = null,
    val isOption: Boolean? = null,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPriceEur: BigDecimal? = null,
    val clientPriceInfo: PriceInfoDto? = null,
    val modelName: String? = null,
    val mainImageId: Long? = null,
    val agencyName: String? = null,
)
