package hr.workspace.boat4you.domains.reservation.jpa

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

@Repository
interface ReservationPaymentPhaseRepository : JpaRepository<ReservationPaymentPhase, Long> {
    fun findByStripeSessionIdOrderByDeadlineAsc(stripeSessionId: String): List<ReservationPaymentPhase>

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

    /**
     * Row locks (`SELECT ... FOR UPDATE`) on the phases a card checkout pays (Revolut plan 2A.4). A phase that is already
     * managed in the transaction keeps its loaded state (Hibernate does not refresh it) — check the current paid state
     * with [countByIdInAndPaidOnIsNotNull] after taking the locks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByIdIn(ids: Collection<Long>): List<ReservationPaymentPhase>

    /** How many of [ids] are paid according to the database right now (a scalar query: no persistence-context state). */
    fun countByIdInAndPaidOnIsNotNull(ids: Collection<Long>): Long
}
