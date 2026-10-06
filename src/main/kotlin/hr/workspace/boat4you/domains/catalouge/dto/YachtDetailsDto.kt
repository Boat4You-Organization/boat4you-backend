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
    /**
     * Set only when this boat is a second listing of a boat another channel lists (26.9.2026 audit B17): the slug of
     * the copy the listings and the sitemap show - the boat page's canonical target.
     */
    val listingCanonicalSlug: String? = null,
    /**
     * True when the undated listings (and therefore the sitemaps, which walk `/public/yachts`) show this boat: it has
     * an offer starting today or later that is not UNAVAILABLE - the undated predicate of `/public/yachts`. False for
     * a boat no partner offers anything for any more (Mario 27.9.2026): it is in no listing or sitemap, and its page
     * stays reachable as an inquiry form without price or calendar. Null only if the check could not run.
     */
    val hasBookableFutureOffer: Boolean? = null,
    /**
     * Capacity as this listing's own partner gives it (capacity contract v1, 2.1): every figure with its sanitized
     * partner note and the parts that add up to it. The flat cabins / berths / wc / maxPersons / crewNumber above stay
     * for older clients. Never carries the partner's internal remark.
     */
    val capacity: CapacityDto? = null,
    /** Sails, engine and draught as the partner gives them; mainSailType above stays the filter enum. */
    val rig: RigDto? = null,
)

data class CustomYachtDetailsDto(
    val lowPrice: BigDecimal,
    val lowPriceInfo: PriceInfoDto?,
    val priceDescription: String?,
    val videoUrl: String?,
    val hasBrochure: Boolean,
    /**
     * Free-text "Saloon and Cabins" amenities — the public Amenities tab
     * splits this on newlines and renders each non-empty line as a
     * checkmark item. Replaces the predefined equipment dropdown for
     * custom yachts where the boat owner sends a one-off list.
     */
    val amenitiesText: String? = null,
    /** Free-text "Entertainment" toys, same multi-line treatment. */
    val toysText: String? = null,
    /** Free-text engine descriptor — public DetailsTab renders this in
     *  the Engine row instead of "{enginePower} kW" when present. */
    val engineText: String? = null,
)
