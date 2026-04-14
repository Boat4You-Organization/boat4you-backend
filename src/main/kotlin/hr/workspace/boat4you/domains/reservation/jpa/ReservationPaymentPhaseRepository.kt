package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

@Repository
interface ReservationPaymentPhaseRepository : JpaRepository<ReservationPaymentPhase, Long> {
    fun findByStripeSessionIdOrderByDeadlineAsc(stripeSessionId: String): List<ReservationPaymentPhase>

    fun findByVivaOrderCodeOrderByDeadlineAsc(vivaOrderCode: String): List<ReservationPaymentPhase>

    @Query(
        """
        SELECT pp FROM ReservationPaymentPhase pp
        JOIN FETCH pp.reservationFlow
        WHERE pp.deadline = :futureDeadline
        AND pp.paidOn IS NULL
        AND (SELECT COUNT(pp2) FROM ReservationPaymentPhase pp2 WHERE pp2.reservationFlow.previousFlow.id = pp.reservationFlow.id) = 0
        """,
    )
    fun findPendingPayments(futureDeadline: LocalDate): List<ReservationPaymentPhase>

    @Query(
        """
            SELECT SUM(pp.amount) FROM ReservationPaymentPhase pp
            WHERE pp.reservationFlow.id IN (:reservationFlowIds)
            AND pp.paidOn IS NOT NULL
        """,
    )
    fun calculateTotalPaid(reservationFlowIds: Set<Long>): BigDecimal

    fun findByReservationFlowIdIn(reservationFlowIds: Set<Long>): List<ReservationPaymentPhase>
}
