package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "yacht_extras")
open class YachtExtra {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    @Column(name = "yacht_id", insertable = false, updatable = false)
    var yachtId: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "extras_id")
    open var extras: Extra? = null

    @Column(name = "extras_id", insertable = false, updatable = false)
    var extrasId: Long? = null

    @Column(name = "name", length = Integer.MAX_VALUE)
    open var name: String? = null

    @NotNull
    @Column(name = "price", nullable = false)
    open var price: BigDecimal? = null

    @NotNull
    @Column(name = "payable_in_base", nullable = false)
    open var payableInBase: Boolean? = false

    @Enumerated
    @NotNull
    @Column(name = "unit", nullable = false)
    open var unit: ExtrasUnitType? = null

    @NotNull
    @Column(name = "obligatory", nullable = false)
    open var obligatory: Boolean? = false

    @Column(name = "external_unit", length = Integer.MAX_VALUE)
    open var externalUnit: String? = null

    /**
     * might be duplicate
     */
    @Column(name = "external_id")
    open var externalId: Long? = null

    @Column(name = "valid_from")
    open var validFrom: LocalDate? = null

    @Column(name = "valid_to")
    open var validTo: LocalDate? = null

    @Enumerated
    @NotNull
    @Column(name = "type", nullable = false)
    open var type: ExtrasType? = null

    @Column(name = "valid_for_bases")
    open var validForBases: List<Long>? = null

    /**
     * Free-form partner description (e.g. "FUN PACK A [Jokerboat Coaster
     * 470 + 70HP; deposit €1000; Croatian waters only]"). Sourced from
     * MMK `Extras.description` / Nausys yacht-services catalogue. Null
     * when partner sent no description or pre-V1_52 row hasn't been
     * re-synced. Frontend renders as small print under the extras name.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    open var description: String? = null

    /**
     * Refined payment classification — see ExtraPaymentType for semantics.
     * Backfilled by V1_57 + populated by sync mappers via classify().
     */
    @Enumerated
    @Column(name = "payment_type")
    open var paymentType: ExtraPaymentType? = null

    /**
     * True when this extras row's `validFrom..validTo` window overlaps the
     * `[bookingFrom, bookingTo]` charter dates. Treats either bound being
     * null as "always valid on that side". Used by reservation mappers to
     * hide partner price rows that belong to a different season/year so
     * customers don't see a mix of 2026/2027/2028 prices on a single
     * booking. Mario rule (3.5.2026): partner mijenja cijene po periodu.
     */
    fun appliesToPeriod(bookingFrom: java.time.LocalDate, bookingTo: java.time.LocalDate): Boolean {
        val fromOk = validFrom?.let { !it.isAfter(bookingTo) } ?: true
        val toOk = validTo?.let { !it.isBefore(bookingFrom) } ?: true
        return fromOk && toOk
    }

    fun shouldDisplay(): Boolean {
        // Show ANY extras the partner sent — old filter required either
        // (a) match against the b4y canonical catalogue (extrasId != null), or
        // (b) obligatory + non-zero price.
        // That hid 90%+ of partner extras (Sea Dreams 3117 has 9 yacht_extras
        // in DB, all extrasId=null and obligatory=false → 0 visible). Brokers
        // and customers expect to see exactly what the partner offers, so we
        // now display everything that has a usable name. Free items (price=0)
        // still surface — the frontend renders them as "included".
        return !name.isNullOrBlank()
    }

    fun extrasKey(): String {
        return extrasId?.toString() ?: name!!
    }
}
