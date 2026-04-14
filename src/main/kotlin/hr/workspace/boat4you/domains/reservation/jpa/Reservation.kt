package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import jakarta.persistence.CascadeType
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

@Entity
@Table(name = "reservation")
open class Reservation {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "reservation_flow_id", nullable = false)
    open var reservationFlow: ReservationFlow? = null

    @NotNull
    @Column(name = "date_from", nullable = false)
    open var dateFrom: LocalDateTime? = null

    @NotNull
    @Column(name = "date_to", nullable = false)
    open var dateTo: LocalDateTime? = null

    @Column(name = "reservation_number", length = 9, unique = true)
    open var reservationNumber: String? = null

    @NotNull
    @Column(name = "external_id", nullable = false)
    open var externalId: Long? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response")
    open var response: String? = null

    /**
     * represents mapped external status
     */
    @Enumerated
    @NotNull
    @Column(name = "status", nullable = false)
    open var status: OfferStatus? = null

    @Enumerated
    @NotNull
    @Column(name = "sys_status", nullable = false)
    open var sysStatus: ReservationStatus? = null

    /**
     * Original external status int for MMK values, string for Nausys
     */
    @Size(max = 30)
    @Column(name = "external_status", length = 30)
    open var externalStatus: String? = null

    @Column(name = "option_expires_at")
    open var optionExpiresAt: LocalDateTime? = null

    /**
     * reservation code mmk, uuid nausys
     */
    @Size(max = 100)
    @NotNull
    @Column(name = "external_reservation_code", nullable = false, length = 100)
    open var externalReservationCode: String? = null

    @NotNull
    @Column(name = "external_created_at", nullable = false)
    open var externalCreatedAt: LocalDateTime? = null

    @NotNull
    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant? = null

    /**
     * bareboat, crewed, etc
     */
    @Enumerated
    @Column(name = "product")
    open var product: CharterType? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_from", nullable = false)
    open var locationFrom: Location? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_to", nullable = false)
    open var locationTo: Location? = null

    @NotNull
    @Column(name = "base_price", nullable = false)
    open var basePrice: BigDecimal? = null

    @NotNull
    @Column(name = "client_price", nullable = false)
    open var clientPrice: BigDecimal? = null

    @Column(name = "commission")
    open var commission: BigDecimal? = null

    @Column(name = "deposit")
    open var deposit: BigDecimal? = null

    @NotNull
    @Column(name = "total_price", nullable = false)
    open var totalPrice: BigDecimal? = null

    @Size(max = 500)
    @Column(name = "payment_note", length = 500)
    open var paymentNote: String? = null

    @Size(max = 3)
    @Column(name = "currency", length = 3)
    open var currency: String? = null

    @Size(max = 200)
    @Column(name = "bank_details", length = 200)
    open var bankDetails: String? = null

    @Size(max = 500)
    @Column(name = "note", length = 500)
    open var note: String? = null

    @Column(name = "discount")
    open var discount: BigDecimal? = null

    @OneToMany(mappedBy = "reservation", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var externalReservationPaymentPlans: MutableSet<ExternalReservationPaymentPlan> = mutableSetOf()

    @OneToMany(mappedBy = "reservation", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var externalReservationExtras: MutableSet<ExternalReservationExtra> = mutableSetOf()

    @Size(max = 1000)
    @Column(name = "crew_list_url", length = 1000)
    open var crewListUrl: String? = null
}
