package hr.workspace.boat4you.domains.custompayment.jpa

/**
 * `custom_payment.status` (CHECK in V9_80). PENDING = order created, not paid yet; COMPLETED = paid and every check
 * matched; FAILED / CANCELLED = the Revolut order ended without a payment (or the order could not be created);
 * NEEDS_REVIEW = Revolut reports a payment that does not match our row, or the attempt stayed pending for 48 h.
 */
enum class CustomPaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW,
}
