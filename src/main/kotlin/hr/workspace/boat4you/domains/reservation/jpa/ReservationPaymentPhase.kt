package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "reservation_payment_phase")
class ReservationPaymentPhase : AbstractEntity<Long>() {
    @Column(name = "deadline", columnDefinition = "DATE", nullable = false)
    lateinit var deadline: LocalDate

    @Column(name = "amount", columnDefinition = "DECIMAL", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "paid_on", columnDefinition = "TIMESTAMP", nullable = true)
    var paidOn: Instant? = null

    // The table still has stripe_session_id and stripe_payment_intent_id (both NULL in every row; card payments went
    // live on Mollie). They are not mapped any more.

    /** Card provider that recorded the payment of this phase (`MOLLIE`, V9_123); null for manual payments. */
    @Column(name = "payment_provider", columnDefinition = "VARCHAR(16)", nullable = true)
    var paymentProvider: String? = null

    /** Provider order that paid this phase. Not unique: a pay-full checkout pays several phases with one order. */
    @Column(name = "provider_order_id", columnDefinition = "VARCHAR(64)", nullable = true)
    var providerOrderId: String? = null

    /** Provider payment (charge) id inside [providerOrderId]. */
    @Column(name = "provider_payment_id", columnDefinition = "VARCHAR(64)", nullable = true)
    var providerPaymentId: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "reservation_flow_id", referencedColumnName = "id", nullable = false)
    lateinit var reservationFlow: ReservationFlow

    // Id-based equals/hashCode. F2-036 — earlier the structural comparison
    // used mutable fields (deadline, amount, paidOn, …) as equality key, which:
    //   1. Broke `MutableSet<ReservationPaymentPhase>` semantics on
    //      `ReservationFlow.paymentPhases` once any field was reassigned
    //      (notably `paidOn = Instant.now()` when a payment completes):
    //      the hashCode migrated buckets, leaving the entity unreachable.
    //   2. The `paidOn?.equals(other.paidOn) != true` expression evaluated
    //      to `true` (i.e. NOT equal) when both sides were null — making two
    //      structurally-identical unpaid phases compare unequal.
    //
    // Now id-based: unpersisted entities (id == null) compare by reference
    // identity; persisted entities by id. Standard JPA recommendation that
    // avoids both bugs above.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReservationPaymentPhase) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: javaClass.hashCode()
}
