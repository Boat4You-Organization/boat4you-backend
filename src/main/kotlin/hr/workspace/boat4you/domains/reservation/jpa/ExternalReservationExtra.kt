package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.reservation.enums.QuantityUnit
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
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(name = "external_reservation_extras")
open class ExternalReservationExtra {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "reservation_id", nullable = false)
    open var reservation: Reservation? = null

    @Column(name = "external_id")
    open var externalId: Long? = null

    // Partner-owned text, unbounded on purpose (V9_58). A 222-char MMK name against the former varchar(200) failed
    // bean validation at flush and took whole bookings down after the partner option existed (19.9.2026).
    @Column(name = "name", length = Integer.MAX_VALUE)
    open var name: String? = null

    @Column(name = "quantity")
    open var quantity: BigDecimal? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "unit")
    open var unit: QuantityUnit? = null

    @Column(name = "price")
    open var price: BigDecimal? = null

    @Column(name = "payable_in_base")
    open var payableInBase: Boolean? = null
}
