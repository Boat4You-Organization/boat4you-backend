package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal

data class CustomYachtRequest(
    val name: String?,
    val modelId: Long?,
    val buildYear: Short? = null,
    val launchYear: Short? = null,
    val enginePower: Short? = null,
    val length: BigDecimal? = null,
    val draught: BigDecimal? = null,
    val beam: BigDecimal? = null,
    val waterTank: Int? = null,
    val fuelTank: Int? = null,
    val cabins: Short? = null,
    val berths: Short? = null,
    val crewNumber: Short? = null,
    val maxPersons: Short? = null,
    val defaultCheckin: String? = null,
    val defaultCheckout: String? = null,
    val vesselType: VesselType? = null,
    val countryId: String,
    val lowPrice: BigDecimal,
    val priceDescription: String? = null,
    val descriptions: Map<String, String>? = null,
    val videoUrl: String? = null,
    val equipmentIds: Set<Long>? = null,
    val manufacturerId: Long? = null,
    val manufacturerName: String? = null,
    val modelName: String? = null,
)
