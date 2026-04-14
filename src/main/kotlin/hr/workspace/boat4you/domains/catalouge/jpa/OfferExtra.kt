package hr.workspace.boat4you.domains.catalouge.jpa

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

    @Enumerated
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

    fun shouldDisplay(): Boolean {
        return extrasId != null || (obligatory == true && price != null && price != BigDecimal.ZERO)
    }

    fun extrasKey(): String {
        return extrasId?.toString() ?: name!!
    }
}
