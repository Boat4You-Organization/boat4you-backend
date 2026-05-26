package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal

data class CustomYachtDetailsResponse(
    val id: Long,
    val name: String,
    val manufacturerId: Long?,
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
    val maxPersons: Short? = null,
    val defaultCheckin: String? = null,
    val defaultCheckout: String? = null,
    val vesselType: VesselType? = null,
    val countryId: String,
    /** Marina-tier location id in `l-{N}` format. May be null for legacy
     * yachts created before the marina selector was wired up — those
     * default to empty in the admin form so the user is forced to pick one
     * on next save. */
    val locationId: String?,
    val lowPrice: BigDecimal,
    val descriptions: Map<String, String>? = null,
    val videoUrl: String? = null,
    val equipment: Set<YachtEquipmentDto>? = mutableSetOf(),
    val yachtImages: List<YachtImageDto>?,
    val hasBrochure: Boolean,
    val crewNumber: Short? = null,
    val priceDescription: String? = null,
    /** Free-text amenities the public Amenities tab parses line-by-line. */
    val amenitiesText: String? = null,
    val toysText: String? = null,
    /** Free-text engine descriptor — admin types verbatim, public detail
     *  renders as-is in the Specifications row. */
    val engineText: String? = null,
    val slug: String,
)
