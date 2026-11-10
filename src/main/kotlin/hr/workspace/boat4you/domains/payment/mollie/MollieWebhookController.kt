package hr.workspace.boat4you.domains.payment.mollie

import hr.workspace.boat4you.domains.payment.mollie.MolliePaymentHandler.HandlerResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Mollie webhooks (`permitAll` in SecurityConfiguration). Mollie POSTs only `id=tr_…` (form encoded) and signs
 * nothing, so the body is NEVER trusted: the owner re-reads the payment from the Mollie API with our key; a forged id
 * can at most trigger that read. Unknown ids are answered 200 too (Mollie's advice: do not reveal which ids exist).
 *
 * Answers: 200 = handled / not ours; 503 = transient, Mollie retries (up to 10 times over ~26 h); 500 = unexpected
 * failure, logged and also retried.
 */
@Tag(name = "Payments")
@RestController
@RequestMapping("/webhooks/mollie")
class MollieWebhookController(
    private val properties: MollieProperties,
    private val handlers: List<MolliePaymentHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Operation(summary = "Called by Mollie when a payment changes (status, refund, chargeback); body carries only the id")
    @PostMapping
    fun handleMollieWebhook(
        @RequestParam(name = "id", required = false) id: String?,
    ): ResponseEntity<Void> {
        val paymentId = id?.trim().orEmpty()
        if (!properties.enabled) {
            log.warn("Mollie webhook ignored: Mollie is not configured on this node")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
        if (!MollieClient.isValidPaymentId(paymentId)) {
            log.info("Mollie webhook without a usable payment id — ignored")
            return ResponseEntity.ok().build()
        }
        val owner = handlers.firstOrNull { it.ownsPayment(paymentId) }
        if (owner == null) {
            log.info("Mollie webhook for a payment no feature owns ({}) — ignored", paymentId)
            return ResponseEntity.ok().build()
        }
        val result =
            try {
                owner.onPaymentChanged(paymentId)
            } catch (e: Exception) {
                log.error("Mollie webhook for {} failed; Mollie will retry", paymentId, e)
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
            }
        return when (result) {
            HandlerResult.HANDLED -> ResponseEntity.ok().build()
            HandlerResult.TRANSIENT -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
    }
}
