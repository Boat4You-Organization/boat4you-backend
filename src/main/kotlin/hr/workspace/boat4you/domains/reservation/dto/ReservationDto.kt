package hr.workspace.boat4you.domains.reservation.dto

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import java.math.BigDecimal
import java.time.LocalDateTime

data class ReservationDto(
    val id: Long,
    val reservationFlowId: Long,
    val yachtId: Long,
    val dateFrom: LocalDateTime,
    val dateTo: LocalDateTime,
    val totalPrice: BigDecimal,
    val paymentPhases: List<PaymentPhaseDto>?,
    val currency: String,
    val status: OfferStatus,
    val expiresAt: LocalDateTime?,
    val reservationNumber: String?,
)
