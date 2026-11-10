package hr.workspace.boat4you.domains.payment.mollie

/**
 * A feature that creates Mollie payments (custom `/pay` payments, online-booking card payments) and therefore owns the
 * webhook calls of those payments. [MollieWebhookController] asks every handler [ownsPayment] and gives the call to
 * the first owner.
 */
interface MolliePaymentHandler {
    /** True when this feature created the payment (Mollie payment id known in its own table). */
    fun ownsPayment(paymentId: String): Boolean

    /**
     * Mollie says "something changed" (status, refund, chargeback) and sends ONLY the id: the handler re-reads the
     * payment from the Mollie API and must be idempotent. [HandlerResult.TRANSIENT] = could not decide now (Mollie
     * unreachable…); the controller answers 503 and Mollie retries.
     */
    fun onPaymentChanged(paymentId: String): HandlerResult

    enum class HandlerResult { HANDLED, TRANSIENT }
}
