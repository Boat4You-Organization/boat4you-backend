package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.MatchKind
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import java.math.BigDecimal
import java.time.Instant
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
    /** Partner berths / WC numbers (matview columns; capacity contract v1). */
    val berths: Short? = null,
    val wc: Short? = null,
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
     * Partner system this yacht is synced from — "MMK" or "NauSys". Admin-only
     * (same SYSTEM_ADMIN gate as agencyName), null for anonymous/customer
     * callers. Lets the broker tell apart duplicate listings of the same
     * physical yacht that exist under both source systems (e.g. MG Yachts
     * "Endurance" as an MMK row and a NauSys row with different prices).
     */
    val sourceSystem: String? = null,
    /**
     * Top N equipment label_codes for this yacht, sorted by Equipment.filterOrder.
     * Drives the small amenity-icon row on the search card. Null when the
     * feature is disabled or yacht has no equipment rows.
     */
    val amenityKeys: List<String>? = null,
    /**
     * The REAL window of the offer slot that matched the user's search — the
     * honest matched dates, NOT the user's searched dates. When these don't
     * equal the requested dates the card renders a "Closest week" badge showing
     * this window. Resolved via exactOrEarliest (exact match preferred,
     * earliest overlapping slot as fallback).
     */
    val offerDateFrom: LocalDate? = null,
    val offerDateTo: LocalDate? = null,
    /**
     * How offerDateFrom/offerDateTo relate to the searched period (Deploy 4):
     * EXACT (no badge) | SHIFTED | SHORTER | LONGER (render "Closest week" +
     * the real window). Null when the user searched without dates or the slot
     * has no window (custom yacht).
     */
    val matchKind: MatchKind? = null,
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
    /**
     * When the boat's own public record last changed (UTC, whole seconds) — name, model, home base, build year,
     * specs, main image, deposit, active / inquiry-only state: what the boat page's title, meta description, canonical
     * slug, share image and spec table show. Written by the V9_71 trigger when the boat is created and on a real change
     * only (a sync re-saving the same values is none). NOT moved by prices, availability, gallery, descriptions,
     * equipment or extras. Null = no change recorded since V9_71 (1.10.2026): the sitemaps then leave `<lastmod>` out
     * (Codex audit N7).
     */
    val updatedAt: Instant? = null,
    /**
     * Capacity as the boat's own partner gives it, brief form (capacity contract v1, 2.2): notes only when short and
     * language-neutral ("4 +2", "(8+2)"), sanitized in the backend; the split (12 + 1 crew, 8 in cabins + 2 in the
     * saloon) as on the boat page. Read from the yacht row (one primary-key lookup per page), so it may be up to one
     * matview refresh ahead of the flat cabins / maxPersons. Null only for a row without a yacht record.
     */
    val capacity: CapacityDto? = null,
    /**
     * ADMIN ONLY (same SYSTEM_ADMIN gate as agencyName): the partner's raw capacity notes and internal remark, for the
     * broker's info icon. Never copied into customer text. Null for anonymous and customer callers.
     */
    val brokerNotes: BrokerNotesDto? = null,
    /**
     * ADMIN ONLY (same SYSTEM_ADMIN gate): Bareboat / Skippered / Crewed for the offer this card shows (the Offers
     * workspace pill, Mario 9.10.2026). Left out of the JSON for everyone else - public answers stay byte for byte.
     */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val offerCharter: OfferCharterDto? = null,
)
