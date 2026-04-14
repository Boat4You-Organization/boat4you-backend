package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal

data class YachtSearchSelectResult(
    val id: Long,
    val yachtName: String,
    val vesselType: VesselType,
    val buildYear: Short?,
    val maxPersons: Short?,
    val cabins: Short?,
    val length: BigDecimal?,
    val modelName: String?,
    val manufacturerName: String?,
    val mainImage: Long?,
    val agencyName: String?,
    val entryType: EntryType,
    val sumLocations: Long?,
    val charterType: CharterType,
    val locationFullName: String,
    val clientPrice: BigDecimal?,
)
