package hr.workspace.boat4you.domains.payment.mollie.booking.jpa

/**
 * `reservation_card_order.status` (CHECK in V9_123). PENDING = Mollie payment created, not paid yet; COMPLETED = paid and
 * the phases are marked; FAILED / CANCELLED = the order ended without a payment; NEEDS_REVIEW = Mollie reports a
 * payment that cannot be applied as is (amount / currency / reference mismatch, phase already paid, cancelled
 * reservation) — an admin decides.
 */
enum class ReservationCardOrderStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW,
}

/**
 * `reservation_card_order.promote_state` (CHECK in V9_123, plan 2A.10 M4): the pay-full "promote to booking" that runs
 * AFTER the payment marking has committed. NULL = no promote needed (the normal per-phase path).
 */
enum class CardOrderPromoteState {
    PENDING,
    DONE,
    FAILED,
}
