package hr.workspace.boat4you.domains.payment.revolut

import hr.workspace.boat4you.domains.payment.revolut.RevolutOrderEventHandler.HandlerResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException

/**
 * Routes a verified Revolut webhook to the feature that owns the order. An order nobody owns is UNKNOWN (204, no
 * retry) — except `ORDER_COMPLETED` of a refund: a refund is an order of its own, so it is fetched ONCE and handed
 * to the owner of its `related_order_id`.
 */
@Component
class RevolutWebhookDispatcher(
    private val handlers: List<RevolutOrderEventHandler>,
    private val client: RevolutMerchantClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatch(
        event: String,
        orderId: String,
    ): DispatchResult {
        handlers.firstOrNull { it.ownsOrder(orderId) }?.let { owner ->
            return when (owner.onEvent(event, orderId)) {
                HandlerResult.HANDLED -> DispatchResult.HANDLED
                HandlerResult.TRANSIENT -> DispatchResult.TRANSIENT
            }
        }
        if (event == RevolutWebhookEvents.ORDER_COMPLETED) {
            val order =
                try {
                    client.getOrder(orderId)
                } catch (e: ResourceAccessException) {
                    log.warn("Revolut webhook {}: order {} not readable now ({})", event, orderId, e.javaClass.simpleName)
                    return DispatchResult.TRANSIENT
                } catch (e: HttpServerErrorException) {
                    log.warn("Revolut webhook {}: order {} not readable now ({})", event, orderId, e.statusCode.value())
                    return DispatchResult.TRANSIENT
                }
            val relatedOrderId = order?.relatedOrderId
            if (order?.type == RevolutOrderTypes.REFUND && !relatedOrderId.isNullOrBlank()) {
                handlers.firstOrNull { it.ownsOrder(relatedOrderId) }?.let { owner ->
                    owner.onRefund(relatedOrderId, order)
                    return DispatchResult.HANDLED
                }
            }
        }
        log.info("Revolut webhook {} for an order no feature owns ({}) — ignored", event, orderId)
        return DispatchResult.UNKNOWN
    }

    enum class DispatchResult { HANDLED, TRANSIENT, UNKNOWN }
}
