package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Mapping for DB view
 */
@Entity
@Immutable
@Table(name = "yacht_search_view")
open class YachtSearchView protected constructor() {
    @Id
    @Column(name = "id")
    open var id: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "yacht_name")
    open var yachtName: String? = null
        protected set

    @Column(name = "location_from")
    open var locationFrom: Long? = null
        protected set

    @Column(name = "location_to")
    open var locationTo: Long? = null
        protected set

    @Column(name = "client_price")
    open var clientPrice: BigDecimal? = null
        protected set

    @Column(name = "list_price")
    open var listPrice: BigDecimal? = null
        protected set

    /**
     * Broker commission for this offer, stored per-day (same treatment as
     * client_price) so the admin card can multiply by nights for the
     * period total. Sourced from `offer.broker_commission` (V1_51), which
     * holds the partner's per-offer commission figure — what we keep
     * from each booking. Null for custom yachts and for offers where the
     * partner didn't include a commission value.
     */
    @Column(name = "broker_commission")
    open var brokerCommission: BigDecimal? = null
        protected set

    @Column(name = "number_of_days")
    open var numberOfDays: Int? = null
        protected set

    @Column(name = "date_from")
    open var dateFrom: LocalDate? = null
        protected set

    @Column(name = "date_to")
    open var dateTo: LocalDate? = null
        protected set

    @Column(name = "build_year")
    open var buildYear: Short? = null
        protected set

    @Column(name = "manufacturer_id")
    open var manufacturerId: Long? = null
        protected set

    @Column(name = "model_id")
    open var modelId: Long? = null
        protected set

    @Column(name = "charter_type")
    open var charterType: CharterType? = null
        protected set

    @Column(name = "vessel_type")
    open var vesselType: VesselType? = null
        protected set

    @Column(name = "mainsail_type")
    open var mainSailType: SailTypeEnum? = null
        protected set

    @Column(name = "max_persons")
    open var maxPersons: Short? = null
        protected set

    @Column(name = "cabins")
    open var cabins: Short? = null
        protected set

    @Column(name = "berths")
    open var berths: Short? = null
        protected set

    @Column(name = "length")
    open var length: BigDecimal? = null
        protected set

    @Column(name = "wc")
    open var wc: Short? = null
        protected set

    @Column(name = "engine_power")
    open var enginePower: Short? = null
        protected set

    @Column(name = "total_locations")
    open var totalLocations: Int? = null
        protected set

    @Column(name = "recommended_score")
    open var recommendedScore: BigDecimal? = null
        protected set

    @Column(name = "lowest_prepayment")
    open var lowestPrepayment: BigDecimal? = null
        protected set

    @Column(name = "model_name")
    open var modelName: String? = null
        protected set

    @Column(name = "main_image")
    open var mainImage: Long? = null
        protected set

    @Column(name = "agency_id")
    open var agencyId: Long? = null
        protected set

    @Column(name = "agency_name")
    open var agencyName: String? = null
        protected set

    @Column(name = "location_full_name", length = Integer.MAX_VALUE)
    open var locationFullName: String? = null
        protected set

    /**
     * Same encoding as `location_full_name` but for the drop-off location.
     * For yachts where pickup == drop-off (most common) this equals
     * `location_full_name`. Mapper compares both and only surfaces a
     * separate `locationTo` DTO when they differ (one-way charter).
     */
    @Column(name = "location_to_full_name", length = Integer.MAX_VALUE)
    open var locationToFullName: String? = null
        protected set

    @Enumerated
    @Column(name = "entry_type")
    open var entryType: EntryType? = null
        protected set

    @Size(max = 255)
    @Column(name = "manufacturer_name")
    open var manufacturerName: String? = null
        protected set

    /**
     * Raw per-offer status (OfferStatus.value). UI groups these into
     * Available / Pre-reserved / Unavailable badges. See [OfferStatus].
     * Custom yachts (entry_type=2) are forced to 1 (FREE) by the view.
     */
    @Column(name = "offer_status")
    open var offerStatus: Int? = null
        protected set

    /**
     * 0/1 mirror of agency.recommended — used by the "Recommended" sort to
     * promote curated partners' yachts to the top of the page, then
     * client_price ASC within each bucket. INT instead of boolean so JPA
     * criteria's MAX() aggregation behaves identically across drivers.
     */
    @Column(name = "agency_recommended")
    open var agencyRecommended: Int? = null
        protected set
}
