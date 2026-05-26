package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
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
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(name = "offer_extras")
open class OfferExtra {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ColumnDefault("nextval('offer_extras_id_seq')")
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @NotNull
    @Column(name = "obligatory", nullable = false)
    open var obligatory: Boolean? = false

    @NotNull
    @Column(name = "price", nullable = false)
    open var price: BigDecimal? = null

    @NotNull
    @Column(name = "payable_in_base", nullable = false)
    open var payableInBase: Boolean? = false

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "extras_id")
    open var extras: Extra? = null

    @Column(name = "extras_id", insertable = false, updatable = false)
    var extrasId: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "offer_id")
    open var offer: Offer? = null

    @Column(name = "name", length = Integer.MAX_VALUE)
    open var name: String? = null

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "unit", nullable = false)
    open var unit: ExtrasUnitType? = null

    @Column(name = "external_unit", length = Integer.MAX_VALUE)
    open var externalUnit: String? = null

    /**
     * might be duplicate!
     */
    @Column(name = "external_id")
    open var externalId: Long? = null

    /**
     * Free-form partner description (e.g. MMK ObligatoryExtra "Croatian
     * Tourist Tax (1.33 € per person/night)" or a payment-condition note).
     * Frontend renders as small print under the extras name.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    open var description: String? = null

    /**
     * Refined payment classification — replaces the overloaded `payableInBase`
     * boolean for customer-facing display. Backfilled by V1_57 + populated by
     * sync mappers via `ExtraPaymentType.classify(...)`.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type")
    open var paymentType: ExtraPaymentType? = null

    fun shouldDisplay(): Boolean {
        // Mirror YachtExtra.shouldDisplay relaxation — show whatever the
        // partner sent so brokers/customers see exactly the partner's offer
        // contents. Free items still surface (frontend → "included" badge).
        return !name.isNullOrBlank()
    }

    fun extrasKey(): String {
        return extrasId?.toString() ?: name!!
    }
}
