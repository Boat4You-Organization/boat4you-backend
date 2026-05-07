package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal
import java.time.LocalDate

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
    /** Drop-off location encoded same as `locationFullName`. Same as
     *  `locationFullName` for non-one-way offers; differs for one-way
     *  charters where pickup and drop-off marinas are not the same. */
    val locationToFullName: String?,
    val clientPrice: BigDecimal?,
    val listPrice: BigDecimal?,
    /** Broker commission per day, min over matching offers (same shape as client/list). */
    val brokerCommission: BigDecimal?,
    val numberOfDays: Int?,
    /** Raw OfferStatus.value (0..9). UI maps to Available / Pre-reserved badges. */
    val offerStatus: Int?,
    /** Earliest matching offer's period — lets the card show a "Closest day"
     * badge when the offer doesn't sit on the exact dates the user searched. */
    val offerDateFrom: LocalDate?,
    val offerDateTo: LocalDate?,
)
