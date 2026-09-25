package hr.workspace.boat4you.domains.review.service

import hr.workspace.boat4you.domains.reservation.events.ReservationPaymentRecordedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Booking review request right after a payment. AFTER_COMMIT: a rolled-back payment never sends; fallbackExecution:
 * the admin endpoints publish after their service transaction already committed. The work is handed to a virtual
 * thread so the Stripe webhook / admin request returns without waiting, and any failure stays in the log — the
 * payment flow cannot fail because of the review e-mail. The service decides eligibility (first payment only).
 */
@Component
class ReviewPaymentListener(
    private val invitationService: ReviewInvitationService,
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onPaymentRecorded(event: ReservationPaymentRecordedEvent) {
        try {
            executor.submit { invitationService.sendBookingInvitationIfEligible(event.reservationId) }
        } catch (e: Exception) {
            log.error("Could not schedule the booking review request for reservation {}", event.reservationId, e)
        }
    }
}
