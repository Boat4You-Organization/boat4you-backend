package hr.workspace.boat4you.domains.payment.revolut

/**
 * A feature that creates Revolut orders (custom `/pay` payments, online-booking card payments) and therefore owns
 * the webhook events of those orders. [RevolutWebhookDispatcher] asks every handler [ownsOrder] and gives the event
 * to the first owner.
 */
interface RevolutOrderEventHandler {
    /** True when this feature created the order (provider order id known in its own table). */
    fun ownsOrder(orderId: String): Boolean

    /**
     * Applies the webhook [event] to the owned order. Revolut sends no event id, may reorder or repeat events, so the
     * handler re-reads the order and must be idempotent. [HandlerResult.TRANSIENT] = could not decide now (Revolut
     * unreachable…); the controller answers 503 and Revolut retries.
     */
    fun onEvent(
        event: String,
        orderId: String,
    ): HandlerResult

    /** A refund order ([refundOrder], `type=refund`) was completed for the owned [originalOrderId]. Default: ignore. */
    fun onRefund(
        originalOrderId: String,
        refundOrder: RevolutOrder,
    ) {}

    enum class HandlerResult { HANDLED, TRANSIENT }
}
