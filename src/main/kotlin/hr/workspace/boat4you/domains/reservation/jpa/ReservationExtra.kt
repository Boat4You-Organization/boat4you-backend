package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.jpa.Extra
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
import jakarta.validation.constraints.Size
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(name = "reservation_extras")
open class ReservationExtra {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "reservation_flow_id")
    open var reservationFlow: ReservationFlow? = null

    @Column(name = "price", nullable = false)
    open var price: BigDecimal? = null

    @Size(max = 255)
    @Column(name = "yacht_extras_key")
    open var yachtExtrasKey: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "extras_id")
    open var extras: Extra? = null

    @Column(name = "name", length = Integer.MAX_VALUE)
    open var name: String? = null

    @Column(name = "obligatory")
    open var obligatory: Boolean? = null

    @Column(name = "payable_at_base")
    open var payableAtBase: Boolean? = null

    @Column(name = "unit_price")
    open var unitPrice: BigDecimal? = null

    @Enumerated
    @Column(name = "unit")
    open var unit: ExtrasUnitType? = null

    /**
     * This is offer extras id or yacht extras id. For troubleshooting
     */
    @Column(name = "source_id")
    open var sourceId: Long? = null

    @Column(name = "external_id")
    open var externalId: Long? = null
}
