package hr.workspace.boat4you.domains.custompayment.exceptions

/** Revolut is not configured on this node (no keys / sandbox in prod) -> 503, code 8001. */
class CustomPaymentsUnavailableException : RuntimeException()

/**
 * Unknown site / link key pair, unknown or expired payment token, or (admin) unknown id -> 404, code 8002. One answer
 * for all of them, so a link key or a token cannot be probed.
 */
class CustomPaymentNotFoundException : RuntimeException()

/** Too many attempts from one e-mail address (5 per hour) -> 429, code 8003. */
class CustomPaymentTooManyException : RuntimeException()

/** Revolut refused or did not answer while creating / reading an order -> 502, code 8004. */
class CustomPaymentProviderException : RuntimeException()
