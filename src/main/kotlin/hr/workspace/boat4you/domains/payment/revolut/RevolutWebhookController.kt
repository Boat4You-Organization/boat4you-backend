package hr.workspace.boat4you.domains.payment.revolut

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.payment.revolut.RevolutWebhookDispatcher.DispatchResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Revolut Merchant webhooks (`permitAll` in SecurityConfiguration, like `/webhooks/stripe`). The signature is checked
 * on the raw bytes BEFORE the body is parsed or anything else is called; an unsigned / stale / tampered request (or
 * any request while the feature is disabled) is a 400 and touches nothing.
 *
 * Answers: 204 = handled or not ours (Revolut stops); 503 = transient, Revolut retries (3× 10 min apart); 500 = an
 * unexpected failure, logged and also retried by Revolut — never turned into a 2xx.
 */
@Tag(name = "Payments")
@RestController
@RequestMapping("/webhooks/revolut")
class RevolutWebhookController(
    private val properties: RevolutProperties,
    private val verifier: RevolutSignatureVerifier,
    private val dispatcher: RevolutWebhookDispatcher,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Operation(summary = "Called by Revolut when the state of a Revolut order changes")
    @PostMapping
    fun handleRevolutWebhook(request: HttpServletRequest): ResponseEntity<Void> {
        // Bounded read: Revolut's body is a few hundred bytes; an anonymous caller must not make us buffer megabytes.
        val rawBody = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (rawBody.size > MAX_BODY_BYTES) {
            log.warn("Revolut webhook rejected: body larger than {} bytes", MAX_BODY_BYTES)
            return ResponseEntity.badRequest().build()
        }
        if (!properties.enabled) {
            log.warn("Revolut webhook rejected: Revolut is not configured on this node")
            return ResponseEntity.badRequest().build()
        }
        val signed =
            verifier.isValid(
                request.getHeader(RevolutSignatureVerifier.TIMESTAMP_HEADER),
                request.getHeader(RevolutSignatureVerifier.SIGNATURE_HEADER),
                rawBody,
                properties.webhookSecret,
            )
        if (!signed) {
            log.warn("Revolut webhook rejected: missing, stale or invalid signature")
            return ResponseEntity.badRequest().build()
        }

        val payload =
            try {
                objectMapper.readValue(rawBody, RevolutWebhookPayload::class.java)
            } catch (e: JacksonException) {
                log.warn("Revolut webhook rejected: signed body is not a webhook payload ({})", e.javaClass.simpleName)
                return ResponseEntity.badRequest().build()
            }
        val event = payload.event?.trim().orEmpty()
        val orderId = payload.orderId?.trim().orEmpty()
        if (event.isEmpty() || !RevolutMerchantClient.isValidOrderId(orderId)) {
            log.info("Revolut webhook without an event or a usable order id — ignored")
            return ResponseEntity.noContent().build()
        }

        val result =
            try {
                dispatcher.dispatch(event, orderId)
            } catch (e: Exception) {
                log.error("Revolut webhook {} for order {} failed; Revolut will retry", event, orderId, e)
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
            }
        return when (result) {
            DispatchResult.HANDLED, DispatchResult.UNKNOWN -> ResponseEntity.noContent().build()
            DispatchResult.TRANSIENT -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 65_536
    }
}
