package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.reservation.enums.QuantityUnit
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
import jakarta.validation.constraints.Size
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

    @Size(max = 200)
    @Column(name = "name", length = 200)
    open var name: String? = null

    @Column(name = "quantity")
    open var quantity: BigDecimal? = null

    @Enumerated
    @Column(name = "unit")
    open var unit: QuantityUnit? = null

    @Column(name = "price")
    open var price: BigDecimal? = null

    @Column(name = "payable_in_base")
    open var payableInBase: Boolean? = null
}
