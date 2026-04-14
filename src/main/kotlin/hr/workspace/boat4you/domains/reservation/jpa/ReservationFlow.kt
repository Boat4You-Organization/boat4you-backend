package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.reservation.enums.ReservationFlowStatus
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
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
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Represents started flow of the reservation
 */
@Entity
@Table(name = "reservation_flow")
open class ReservationFlow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    open var id: Long? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "offer_id", nullable = false)
    open var offer: Offer? = null

    @NotNull
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant? = null

    // TODO should we remove this?
    @Enumerated
    @Column(name = "status")
    open var status: ReservationFlowStatus? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "created_by", nullable = false)
    open var createdBy: UserEntity? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "user_id", nullable = false)
    open var user: UserEntity? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "email", nullable = false)
    open var email: String? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false)
    open var name: String? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "surname", nullable = false)
    open var surname: String? = null

    @Size(max = 63)
    @Column(name = "phone", nullable = false, length = 63)
    open var phoneNumber: String? = null

    @Size(max = 1000)
    @Column(name = "request", length = 1000)
    open var specialRequest: String? = null

    @OneToMany(mappedBy = "reservationFlow")
    open var reservationExtras: MutableSet<ReservationExtra> = mutableSetOf()

    @OneToMany(mappedBy = "reservationFlow")
    open var paymentPhases: MutableSet<ReservationPaymentPhase> = mutableSetOf()

    /**
     * Represents total price calculated by boat4you system
     */
    @NotNull
    @Column(name = "calculated_total_price", nullable = false)
    open var calculatedTotalPrice: BigDecimal? = null

    @Size(max = 1000)
    @Column(name = "cancelation_request", length = 1000)
    open var cancelationRequest: String? = null

    @Column(name = "cancelation_request_at")
    open var cancelationRequestAt: LocalDateTime? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "previous_flow_id")
    open var previousFlow: ReservationFlow? = null

    @NotNull
    @ColumnDefault("0")
    @Column(name = "agency_commission", nullable = false)
    open var agencyCommission: BigDecimal? = null

    fun getFullName(): String {
        return "$name $surname"
    }
}
