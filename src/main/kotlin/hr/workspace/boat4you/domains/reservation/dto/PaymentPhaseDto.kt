package hr.workspace.boat4you.domains.reservation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class PaymentPhaseDto(
    @Schema(name = "Payment phase ID", description = "Empty if a price-based calculation is requested", example = "1", required = false)
    val id: Long? = null,
    val deadline: LocalDate,
    val amount: BigDecimal,
    @Schema(name = "Paid on", description = "Non-null if the phase has been paid", required = false)
    val paidOn: LocalDateTime? = null,
    @Schema(name = "Stripe Payment Intent ID", description = "Non-null if the phase has been paid by Stripe", required = false)
    val stripePaymentIntentId: String? = null,
)
