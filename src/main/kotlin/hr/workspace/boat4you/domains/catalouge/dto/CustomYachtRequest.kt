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
    /**
     * Marina-tier location id in `l-{N}` format. Required so the custom
     * yacht is pinned to a real marina and shows up in the public search
     * under both the parent country/region (via the predicate fallback in
     * YachtQueryingService) and on the marina page itself. Without this
     * the yacht would default to the country-tier Location row, which made
     * it land on the wrong place visually (e.g. "Asker Marina, Norway"
     * popped up because Country.id 86 collides with Location.id 86).
     */
    val locationId: String,
    val lowPrice: BigDecimal,
    val priceDescription: String? = null,
    /** Free-text "Saloon and Cabins" amenities — newline-separated. */
    val amenitiesText: String? = null,
    /** Free-text "Entertainment" toys — newline-separated. */
    val toysText: String? = null,
    /** Free-text engine descriptor (e.g. "2x Volvo IPS 1050"). */
    val engineText: String? = null,
    val descriptions: Map<String, String>? = null,
    val videoUrl: String? = null,
    val equipmentIds: Set<Long>? = null,
    val manufacturerId: Long? = null,
    val manufacturerName: String? = null,
    val modelName: String? = null,
)
