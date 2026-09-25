package hr.workspace.boat4you.domains.reservation.events

/**
 * A payment instalment of this reservation was recorded as paid (Stripe webhook, admin bank-transfer confirm,
 * admin mark-paid). Published inside the payment transaction; listeners that do follow-up work (e.g. the review
 * request e-mail) must run after the commit and must never be able to fail the payment.
 */
data class ReservationPaymentRecordedEvent(
    val reservationId: Long,
)
