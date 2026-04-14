package hr.workspace.boat4you.domains.reservation.controllers

import com.stripe.net.Webhook
import hr.workspace.boat4you.domains.reservation.service.StripePaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Payments")
@Validated
@Controller
@RequestMapping("/webhooks/stripe")
class StripeWebhookController(
    private val paymentService: StripePaymentService,
    @Value("\${application.stripe.webhook-secret}") private val webhookSecret: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Operation(summary = "Called by Stripe when payment process is done")
    @PostMapping
    fun handleStripeWebhook(request: HttpServletRequest): ResponseEntity<String> {
        val payload = request.reader.readText()
        val sigHeader = request.getHeader("Stripe-Signature")

        val event =
            try {
                Webhook.constructEvent(payload, sigHeader, webhookSecret)
            } catch (e: Exception) {
                logger.error("Invalid Stripe webhook signature: ${e.message}")
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature")
            }

        paymentService.handleWebhookEvent(event)

        return ResponseEntity.ok("Webhook received")
    }
}
