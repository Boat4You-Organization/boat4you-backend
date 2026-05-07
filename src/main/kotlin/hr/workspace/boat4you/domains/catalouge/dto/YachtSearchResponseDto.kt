package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class YachtSearchResponseDto(
    val id: Long,
    val slug: String,
    val name: String?,
    val location: LocationDto? = null,
    /**
     * Drop-off location for one-way charter offerings (yacht starts at
     * `location` on `offerDateFrom` and is returned to `locationTo` on
     * `offerDateTo`). Null when pickup == drop-off (same marina), which
     * is the common case. Frontend renders "{location.name} » {locationTo.name}"
     * when set so the user immediately sees the one-way arrangement
     * before clicking through to the detail page.
     */
    val locationTo: LocationDto? = null,
    val charterType: CharterType? = null,
    val vesselType: VesselType? = null,
    val buildYear: Short? = null,
    val maxPersons: Short? = null,
    val cabins: Short? = null,
    val length: BigDecimal? = null,
    val lengthInfo: MeasurementUnitDto? = null,
    val totalLocations: Int? = null,
    /**
     * Legacy boolean — true if current best offer is OPTION/OPTION_WAITING.
     * Kept for backwards compatibility with existing consumers.
     */
    val isOption: Boolean? = null,
    /**
     * Full offer status so the UI can paint granular Available /
     * Pre-reserved / Unavailable badges instead of a two-state boolean.
     */
    val offerStatus: OfferStatus? = null,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPriceEur: BigDecimal? = null,
    val clientPriceInfo: PriceInfoDto? = null,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val listPriceEur: BigDecimal? = null,
    val listPriceInfo: PriceInfoDto? = null,
    val numberOfDays: Int? = null,
    val modelName: String? = null,
    val mainImageId: Long? = null,
    val agencyName: String? = null,
    /**
     * Broker commission amount for a single charter day, in the requested
     * currency. Admin-only — null for anonymous or customer callers so the
     * public search never leaks our fee. Multiply by numberOfDays for the
     * period total; divide by clientPrice for the percentage.
     */
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val agencyCommissionEur: BigDecimal? = null,
    /**
     * Top N equipment label_codes for this yacht, sorted by Equipment.filterOrder.
     * Drives the small amenity-icon row on the search card. Null when the
     * feature is disabled or yacht has no equipment rows.
     */
    val amenityKeys: List<String>? = null,
    /**
     * Start/end of the offer period that matched the user's search (within the
     * ±3 day flex window). When these don't equal the user's requested dates
     * the card renders a "Closest day" badge.
     */
    val offerDateFrom: LocalDate? = null,
    val offerDateTo: LocalDate? = null,
    /**
     * When the yacht is under option (isOption == true) AND the partner
     * sync captured an expiry timestamp on the external_reservations row,
     * this is the precise deadline after which the option lapses back to
     * available. Shown to admin brokers as "Option expires DD.MM.YYYY HH:mm"
     * next to "Add to offer" so they know how long they can hold a
     * competing conversation before the yacht frees up.
     *
     * Null for non-optioned yachts and for optioned yachts whose partner
     * row didn't carry an expiry (some older MMK rows, or options the
     * partner flagged without a timestamp).
     */
    val optionExpiresAt: LocalDateTime? = null,
    /**
     * True when this row represents a custom (admin-managed) yacht — the
     * search card swaps the green "Available" badge for a blue "On request"
     * label so users know they need to inquire instead of book directly.
     * Sourced from yacht_search_view.entry_type == CUSTOM (2).
     */
    val custom: Boolean? = null,
)
