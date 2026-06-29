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
    /**
     * Post-CASE offer status as Int code (OfferStatus.value): FREE=1, OPTION=2,
     * OPTION_WAITING=3, RESERVED=5, SERVICE=7 — everything else collapses to
     * FREE. Not the raw column (which is varchar/STRING since V1_90) but the
     * projection emitted by [YachtQueryingService.getYachts]'s CASE expression.
     */
    val offerStatus: Int?,
    /** Earliest matching offer's period — lets the card show a "Closest day"
     * badge when the offer doesn't sit on the exact dates the user searched. */
    val offerDateFrom: LocalDate?,
    val offerDateTo: LocalDate?,
    /** Multi-week covering sums: SUM over offers fully inside the searched period of
     *  (per-day × days) for client/list/commission, and of days. Used to show the true
     *  multi-week total when weekly offers tile the request and no exact-period offer exists.
     *  Null for a dateless search. See [YachtQueryingService.coveringPeriodTotal]. */
    val coveringClientTotal: BigDecimal?,
    val coveringListTotal: BigDecimal?,
    val coveringCommissionTotal: BigDecimal?,
    val coveringNights: Int?,
)
