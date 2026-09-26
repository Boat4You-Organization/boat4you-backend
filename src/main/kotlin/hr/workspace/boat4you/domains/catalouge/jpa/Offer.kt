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

    @Enumerated(EnumType.STRING)
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

    /**
     * One entry per charge the client can meet on this offer, paired with "has
     * dearer options" (only ever true for optional rows).
     *
     * Obligatory rows are what the partner bills, one charge each: MMK's
     * obligatoryExtrasPrice and NauSys's advance total count every row they list
     * (26.9.2026: all 266 MMK and 48 NauSys offers where our catalogue key put two
     * of them together). So they are told apart by partner identity, not by
     * [OfferExtra.extrasKey] — the fuzzy catalogue match that put "Skipper's
     * liability insurance" under Skipper and charged only one of the two.
     * One exception, pending the owner's call: rows that differ only by a season
     * qualifier ("APA / High season (30%)", "APA / Low season II (30%)") are kept
     * as one item, the dearest, as before (MMK lists and bills them all).
     *
     * Optional rows: unchanged — the cheapest per catalogue key, flagged when
     * dearer variants exist, and hidden behind an obligatory row with that key.
     */
    fun filterDuplicateExtras(): List<Pair<OfferExtra, Boolean>> {
        // Stable order: offerExtras has no @OrderBy, and which row survived a
        // collapse used to depend on load order.
        val (obligatoryRows, optionalRows) =
            offerExtras.sortedBy { it.id ?: Long.MAX_VALUE }.partition { it.obligatory == true }

        val obligatory =
            obligatoryRows
                .distinctBy { it.partnerIdentity() }
                .groupBy { it.extrasKey() to seasonVariantStem(it.name) }
                .values
                .flatMap { rows ->
                    // Only a real season qualifier makes rows variants of one item; a
                    // difference in case or punctuation alone ("Preparation fee" /
                    // "Preparation Fee") is still two partner charges.
                    val seasonVariants =
                        rows.mapTo(HashSet()) { it.name?.trim() }.size > 1 &&
                            rows.any { SEASON_QUALIFIER.containsMatchIn(it.name ?: "") }
                    if (seasonVariants) listOf(rows.maxBy { it.price ?: BigDecimal.ZERO }) else rows
                }.map { it to false }

        val obligatoryKeys = obligatory.mapTo(HashSet()) { it.first.extrasKey() }
        val optional =
            optionalRows
                .filter { it.extrasKey() !in obligatoryKeys }
                .groupBy { it.extrasKey() }
                .values
                .map { extras ->
                    val minPrice = extras.minBy { it.price ?: BigDecimal.ZERO }
                    val hasHigherPrices = extras.any { (it.price ?: BigDecimal.ZERO) > (minPrice.price ?: BigDecimal.ZERO) }
                    minPrice to hasHigherPrices
                }

        return obligatory + optional
    }

    companion object {
        private val SEASON_QUALIFIER =
            Regex("\\b(high|low|mid|middle|peak|shoulder|off|pre|post)[\\s-]*season\\b(\\s+[ivx]+\\b)?", RegexOption.IGNORE_CASE)
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** The name with any season qualifier removed, for spotting seasonal variants of one item. */
        internal fun seasonVariantStem(name: String?): String =
            (name ?: "").replace(SEASON_QUALIFIER, " ").lowercase().replace(NON_ALNUM, " ").trim()
    }
}
