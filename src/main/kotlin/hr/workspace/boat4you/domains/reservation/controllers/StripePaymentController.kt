package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionDto
import hr.workspace.boat4you.domains.reservation.dto.CheckoutSessionStatusEnum
import hr.workspace.boat4you.domains.reservation.service.StripePaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "Payments")
@Validated
@Controller
@RequestMapping("/secured/payments/stripe")
@PreAuthorize("isAuthenticated()")
class StripePaymentController(
    private val paymentService: StripePaymentService,
) {
    @Operation(summary = "Create a Stripe checkout session")
    @PostMapping("/create-checkout-session/{reservationId}")
    fun createCheckoutSession(
        @PathVariable reservationId: Long,
        @Parameter(
            description = "Used if user wants to pay the full amount for the booking instead of just the required first part. Used when creating a reservation",
        ) @RequestParam(value = "payFullAmount", required = false) payFullAmount: Boolean? = null,
        @Parameter(description = "Used when paying later installments") @RequestParam(value = "paymentPhaseId", required = false) paymentPhaseId: Long? = null,
    ): ResponseEntity<CheckoutSessionDto> {
        val session = paymentService.initiatePayment(reservationId, payFullAmount, paymentPhaseId)
        return ResponseEntity.ok(CheckoutSessionDto(sessionIdOrOrderCode = session.id, status = CheckoutSessionStatusEnum.PAYMENT_PENDING))
    }

    @Operation(summary = "Check payment status for Stripe", description = "Used when performing card payment via Stripe, after redirect to the success URL")
    @PostMapping("/checkPaymentStatus/{stripeSessionId}")
    fun checkPaymentStatus(
        @PathVariable stripeSessionId: String,
    ): ResponseEntity<CheckoutSessionDto> {
        return ResponseEntity.ok(paymentService.checkPaymentStatus(stripeSessionId))
    }
}
