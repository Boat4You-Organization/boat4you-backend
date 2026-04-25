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

    @Column(name = "stripe_session_id", columnDefinition = "VARCHAR(511)", nullable = true)
    var stripeSessionId: String? = null

    @Column(name = "stripe_payment_intent_id", columnDefinition = "VARCHAR(511)", nullable = true)
    var stripePaymentIntentId: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "reservation_flow_id", referencedColumnName = "id", nullable = false)
    lateinit var reservationFlow: ReservationFlow

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReservationPaymentPhase

        if (!deadline.isEqual(other.deadline)) return false
        if (amount != other.amount) return false
        if (paidOn?.equals(other.paidOn) != true) return false
        if (stripeSessionId != other.stripeSessionId) return false
        if (stripePaymentIntentId != other.stripePaymentIntentId) return false
        if (reservationFlow.id != other.reservationFlow.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deadline.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + (paidOn?.hashCode() ?: 0)
        result = 31 * result + (stripeSessionId?.hashCode() ?: 0)
        result = 31 * result + (stripePaymentIntentId?.hashCode() ?: 0)
        result = 31 * result + reservationFlow.id.hashCode()
        return result
    }
}
