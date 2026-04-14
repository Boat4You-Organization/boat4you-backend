package hr.workspace.boat4you.domains.catalouge.jpa

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

    fun shouldDisplay(): Boolean {
        return extrasId != null || (obligatory == true && price != null && price != BigDecimal.ZERO)
    }

    fun extrasKey(): String {
        return extrasId?.toString() ?: name!!
    }
}
