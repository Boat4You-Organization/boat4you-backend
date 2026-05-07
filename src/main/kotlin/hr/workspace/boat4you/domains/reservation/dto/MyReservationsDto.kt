package hr.workspace.boat4you.domains.reservation.dto

import hr.workspace.boat4you.domains.catalouge.dto.PriceInfoDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import java.math.BigDecimal
import java.time.LocalDateTime

data class MyReservationsDto(
    val reservationId: Long,
    val status: ReservationStatus,
    val createdAt: LocalDateTime,
    val yachtId: Long,
    val yachtName: String,
    val modelName: String,
    val yachtImage: Long,
    val dateFrom: LocalDateTime,
    val dateTo: LocalDateTime,
    val checkin: String?,
    val checkout: String?,
    val locationFrom: String,
    val locationFromCountryCode: String,
    val locationTo: String,
    val locationToCountryCode: String,
    val totalPrice: BigDecimal,
    val totalPriceInfo: PriceInfoDto?,
    // List price (pre-discount) from the external system — populated only if
    // the offer had an ext_base_price greater than the client price.
    val listPrice: BigDecimal?,
    val listPriceInfo: PriceInfoDto?,
    val yachtSlug: String,
    val cancellationRequestAt: LocalDateTime?,
    val cancellationRejectedAt: LocalDateTime? = null,
    val reservationNumber: String?,
    val agencyEmail: String?,
    val agencyPhone: String?,
)
