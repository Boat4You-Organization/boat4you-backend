package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.service.StripePaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

// Public (guest) Stripe checkout endpoint. Mirrors StripePaymentController
// but is allow-listed under /public/** so anonymous bookers can pay without
// having an account yet. Rate limiting / captcha at the gateway — see
// DEPLOY_NOTES.md section A.
@Tag(name = "Payments (public)")
@Validated
@Controller
@RequestMapping("/public/payments/stripe")
class PublicStripePaymentController(
    private val paymentService: StripePaymentService,
) {
    @Operation(summary = "Create a Stripe checkout session (public / guest)")
    @PostMapping("/create-checkout-session/{reservationId}")
    fun createCheckoutSession(
        @PathVariable reservationId: Long,
        @Parameter(description = "Pay the full amount instead of the first installment") @RequestParam(value = "payFullAmount", required = false) payFullAmount: Boolean? = null,
        @Parameter(description = "Pay a specific later installment") @RequestParam(value = "paymentPhaseId", required = false) paymentPhaseId: Long? = null,
        @Parameter(
            description = "Stripe idempotency key (generated once per /payment page mount) — dedupes double-click Session.create calls for 24 h.",
        ) @RequestParam(value = "idempotencyKey", required = false) idempotencyKey: String? = null,
    ): ResponseEntity<CheckoutSessionDto> {
        val session = paymentService.initiatePayment(reservationId, payFullAmount, paymentPhaseId, idempotencyKey)
        return ResponseEntity.ok(
            CheckoutSessionDto(
                sessionIdOrOrderCode = session.id,
                status = CheckoutSessionStatusEnum.PAYMENT_PENDING,
                redirectUrl = session.url,
            ),
        )
    }

    @Operation(summary = "Check Stripe payment status (public / guest)")
    @PostMapping("/checkPaymentStatus/{stripeSessionId}")
    fun checkPaymentStatus(
        @PathVariable stripeSessionId: String,
    ): ResponseEntity<CheckoutSessionDto> {
        return ResponseEntity.ok(paymentService.checkPaymentStatus(stripeSessionId))
    }
}
