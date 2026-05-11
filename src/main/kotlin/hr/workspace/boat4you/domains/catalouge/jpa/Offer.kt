package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Entity
@Table(name = "offer")
open class Offer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ColumnDefault("nextval('offer_id_seq')")
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_from", nullable = false)
    open var locationFrom: Location? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_to", nullable = false)
    open var locationTo: Location? = null

    @NotNull
    @Column(name = "date_from", nullable = false)
    open var dateFrom: LocalDate? = null

    @NotNull
    @Column(name = "date_to", nullable = false)
    open var dateTo: LocalDate? = null

    /**
     * Price with obligatory extras
     */
    @NotNull
    @Column(name = "total_price", nullable = false)
    open var totalPrice: BigDecimal? = null

    /**
     * Base price without extras
     */
    @NotNull
    @Column(name = "client_price", nullable = false)
    open var clientPrice: BigDecimal? = null

    @Column(name = "deposit")
    open var deposit: BigDecimal? = null

    @Column(name = "deposit_insured")
    open var depositInsured: BigDecimal? = null

    @Column(name = "obligatory_extras_price")
    open var obligatoryExtrasPrice: BigDecimal? = null

    @Column(name = "total_discount")
    open var totalDiscount: BigDecimal? = null

    @NotNull
    @Column(name = "status", nullable = false)
    open var status: OfferStatus? = null

    @OneToMany(mappedBy = "offer", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var offerPaymentPlans: MutableSet<OfferPaymentPlan> = mutableSetOf()

    /**
     * standard sat-sat or other
     */
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "type", nullable = false)
    open var type: OfferType? = null

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "product", nullable = false)
    open var product: CharterType? = null

    @OneToMany(mappedBy = "offer", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var offerExtras: MutableList<OfferExtra> = mutableListOf()

    @Size(max = 20)
    @NotNull
    @Column(name = "checkin", nullable = false, length = 20)
    open var checkin: String? = null

    @Size(max = 20)
    @NotNull
    @Column(name = "checkout", nullable = false, length = 20)
    open var checkout: String? = null

    @Column(name = "ext_base_price")
    open var extBasePrice: BigDecimal? = null

    @Column(name = "ext_client_price")
    open var extClientPrice: BigDecimal? = null

    @Column(name = "ext_total_price")
    open var extTotalPrice: BigDecimal? = null

    /**
     * For Nausys sum of all discounts. Not applying boat4you agency discount
     */
    @Column(name = "ext_total_discount")
    open var extTotalDiscount: BigDecimal? = null

    @Column(name = "ext_discount_perc")
    open var extDiscountPerc: BigDecimal? = null

    @NotNull
    @ColumnDefault("0")
    @Column(name = "agency_commission", nullable = false)
    open var agencyCommission: BigDecimal? = null

    /**
     * Broker commission for this single offer, in the offer's currency.
     * This is "what we (boat4you) keep" per booking — equivalent to
     * `clientPrice - agencyPrice` once the booking goes through.
     *
     * Sourced directly from the partner during offer sync:
     *  - MMK:    `Offer.commissionValue`
     *  - Nausys: `RestYachtReservationPriceInfo.agencyCommission`
     *
     * Null for pre-V1_51 rows (backfilled via next offer sync) and for
     * custom yachts (no partner record).
     */
    @Column(name = "broker_commission")
    open var brokerCommission: BigDecimal? = null

    @Size(max = 50)
    @Column(name = "ext_status", length = 50)
    open var extStatus: String? = null

    fun numberOfDays(): Long {
        return ChronoUnit.DAYS.between(dateFrom, dateTo).coerceAtLeast(1)
    }

    fun pricePerDayEur(): BigDecimal {
        return clientPrice!!.divide(numberOfDays().toBigDecimal(), 2, RoundingMode.HALF_UP)
    }

    fun filterDuplicateExtras(): List<Pair<OfferExtra, Boolean>> {
        return this.offerExtras
            .groupBy { it.extrasKey() }
            .mapValues { (_, extras) ->
                val obligatory = extras.find { it.obligatory == true }

                if (obligatory != null) {
                    obligatory to false // obligatory item, no higher price options
                } else {
                    val minPrice = extras.minByOrNull { it.price ?: BigDecimal.ZERO }!!
                    val hasHigherPrices = extras.any { (it.price ?: BigDecimal.ZERO) > (minPrice.price ?: BigDecimal.ZERO) }
                    minPrice to hasHigherPrices
                }
            }.values
            .toList()
    }
}
