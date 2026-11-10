package hr.workspace.boat4you.domains.payment.mollie.booking.jpa

/**
 * `reservation_card_order.status` (CHECK in V9_123). PENDING = Mollie payment created, not paid yet; COMPLETED = paid and
 * the phases are marked; SUPERSEDED = a newer checkout of the same flow replaced it on OUR side while Mollie still keeps
 * it payable (card payments cannot be cancelled) — NOT final: it is read from Mollie until Mollie itself closes it, and
 * recorded if it is paid after all; FAILED / CANCELLED = Mollie ended it without a payment; NEEDS_REVIEW = Mollie reports a
 * payment that cannot be applied as is (amount / currency / reference mismatch, phase already paid, cancelled
 * reservation) — an admin decides.
 */
enum class ReservationCardOrderStatus {
    PENDING,
    SUPERSEDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW,
}

/** `reservation_card_order.method` (CHECK in V9_124): which Mollie methods the hosted checkout of the order offers. */
enum class BookingCheckoutMethod {
    /** Cards and Apple Pay. */
    CARD,

    /** Klarna only (needs the billing address and order lines). */
    KLARNA,
    ;

    companion object {
        /** NULL / unknown = CARD (rows from before V9_124). */
        fun fromStored(value: String?): BookingCheckoutMethod = entries.firstOrNull { it.name == value } ?: CARD
    }
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
