package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(
    name = "yacht",
)
open class Yacht {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false)
    open var name: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "agency_id")
    open var agency: Agency? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "location_id")
    open var location: Location? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "model_id", nullable = false)
    open var model: Model? = null

    @Column(name = "deposit")
    open var deposit: BigDecimal? = null

    /**
     * Deposit when insurance is applied
     */
    @Column(name = "insured_deposit")
    open var insuredDeposit: BigDecimal? = null

    @Column(name = "build_year")
    open var buildYear: Short? = null

    @Column(name = "launch_year")
    open var launchYear: Short? = null

    @Column(name = "engine_power")
    open var enginePower: Short? = null

    @Column(name = "length")
    open var length: BigDecimal? = null

    @Column(name = "draught")
    open var draught: BigDecimal? = null

    @Column(name = "beam")
    open var beam: BigDecimal? = null

    @Column(name = "water_tank")
    open var waterTank: Int? = null

    @Column(name = "fuel_tank")
    open var fuelTank: Int? = null

    @Column(name = "cabins")
    open var cabins: Short? = null

    @Column(name = "crew_cabins")
    open var crewCabins: Short? = null

    @Column(name = "wc")
    open var wc: Short? = null

    @Column(name = "crew_wc")
    open var crewWc: Short? = null

    @Column(name = "berths")
    open var berths: Short? = null

    @Column(name = "crew_berths")
    open var crewBerths: Short? = null

    /**
     * Max number of people on board
     */
    @Column(name = "max_persons")
    open var maxPersons: Short? = null

    /**
     * Time of detault check-in
     */
    @Size(max = 10)
    @Column(name = "default_checkin", length = 10)
    open var defaultCheckin: String? = null

    /**
     * Time of default check-out
     */
    @Size(max = 10)
    @Column(name = "default_checkout", length = 10)
    open var defaultCheckout: String? = null

    /**
     * Sail type
     */
    @Enumerated
    @Column(name = "mainsail_type")
    open var mainsailType: SailTypeEnum? = null

    @Column(name = "mainsail_area")
    open var mainsailArea: BigDecimal? = null

    /**
     * Sail type
     */
    @Enumerated
    @Column(name = "genoa_type", length = 50)
    open var genoaType: SailTypeEnum? = null

    @Column(name = "genoa_area")
    open var genoaArea: BigDecimal? = null

    /**
     * MMK certificate, Nausys registrationNumber
     */
    @Size(max = 50)
    @Column(name = "registration_number", length = 50)
    open var registrationNumber: String? = null

    /**
     * if created option needs to be approved by charter company
     */
    @Column(name = "option_approval")
    open var optionApproval: Boolean? = null

    /**
     * if you can create fix booking by yourself
     (convert option to reservation) through
     API or Agency portal, or you charter
     company needs to do it for you
     */
    @Column(name = "option_to_reservation")
    open var optionToReservation: Boolean? = null

    /**
     * Commision in total
     */
    @Column(name = "commision")
    open var commision: BigDecimal? = null

    /**
     * MMK has only commision perc. We need to calc commision
     */
    @Column(name = "commision_perc")
    open var commisionPerc: BigDecimal? = null

    /**
     * Exclude agency discount for this ship
     */
    @Column(name = "exclude_discount")
    open var excludeDiscount: Boolean? = null

    /**
     * From Nausys. Not sure if needed
     */
    @Column(name = "max_discount")
    open var maxDiscount: BigDecimal? = null

    /**
     * From Nausys. Not sure if needed
     */
    @Size(max = 50)
    @Column(name = "agency_discount_type", length = 50)
    open var agencyDiscountType: String? = null

    /**
     * From Nausys. Not sure if needed
     */
    @Column(name = "max_discount_from_commision")
    open var maxDiscountFromCommision: BigDecimal? = null

    @OneToMany(mappedBy = "yacht")
    open var yachtImages: MutableSet<YachtImage> = mutableSetOf()

    /**
     * Is bareboat or crewed - Nausys - charterType, MMK - products. Only for sync and debugging
     */
    @Size(max = 250)
    @Column(name = "charter_type", length = 250)
    open var extCharterType: String? = null

    /**
     * Nausys - Indicates whether crewed charter type SKIPPER, SKIPPER_HOSTESS or ALL_INCLUSIVE, Only for sync and debugging
     */
    @Size(max = 20)
    @Column(name = "crewed_type", length = 20)
    open var extCrewedType: String? = null

    /**
     * Yacht type. MMK - Kind can be - Sail boat , Motor boat, Catamaran, Power Catamaran, Gulet, Motorsailer, Motoryacht, Trimaran, Other. Nausys - ???
     */
    @NotNull
    @Enumerated
    @Column(name = "vessel_type", nullable = false)
    open var vesselType: VesselType? = null

    /**
     * custom or external
     */
    @NotNull
    @Enumerated
    @Column(name = "entry_type", nullable = false)
    open var entryType: EntryType? = null

    /**
     * If yacht is deactivated (deleted) by external system.
     */
    @NotNull
    @ColumnDefault("true")
    @Column(name = "sys_active", nullable = false)
    open var sysActive: Boolean? = false

    @OneToMany(mappedBy = "yacht", fetch = FetchType.LAZY)
    open var reservationOptions: MutableSet<ReservationOption> = mutableSetOf()

    /**
     * Locations for custom boats
     */
    @ManyToMany(cascade = [CascadeType.ALL])
    @JoinTable(
        name = "yacht_locations",
        joinColumns = [JoinColumn(name = "yacht_id")],
        inverseJoinColumns = [JoinColumn(name = "location_id")],
    )
    open var locations: MutableSet<Location> = mutableSetOf()

    @OneToMany(mappedBy = "yacht", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var yachtEquipments: MutableSet<YachtEquipment> = mutableSetOf()

    @OneToMany(mappedBy = "yacht", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var yachtExtras: MutableSet<YachtExtra> = mutableSetOf()

    @OneToMany(mappedBy = "yacht", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var yachtCharterTypes: MutableSet<YachtCharterType> = mutableSetOf()

    @Column(name = "main_image_id", nullable = false)
    open var mainImageId: Long? = null

    @OneToMany(mappedBy = "yacht")
    open var yachtTranslations: MutableSet<YachtTranslation> = mutableSetOf()

    @OneToMany(mappedBy = "yacht")
    open var customYachtDetails: MutableSet<CustomYachtDetail> = mutableSetOf()

    @Size(max = 20)
    @Column(name = "deposit_currency", length = 20)
    open var depositCurrency: String? = null

    @Column(name = "crew_number")
    open var crewNumber: Short? = null

    fun isInquireOnly(): Boolean = (optionApproval == true || entryType == EntryType.CUSTOM)
}
