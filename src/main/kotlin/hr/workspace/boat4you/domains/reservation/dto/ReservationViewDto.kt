package hr.workspace.boat4you.domains.reservation.dto

import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * DTO for {@link hr.workspace.boat4you.domains.reservation.jpa.ReservationView}
 */
data class ReservationViewDto(
    val reservationId: Long,
    val reservationFlowId: Long,
    val reservationStatus: OfferStatus,
    val reservationSysStatus: ReservationStatus,
    val reservationCreatedAt: LocalDateTime,
    val reservationOptionExpiresAt: LocalDateTime?,
    val reservationTotalPrice: BigDecimal,
    val reservationDiscount: BigDecimal?,
    val reservationExternalId: Long,
    val reservationExternalReservationCode: String,
    val reservationNumber: String?,
    val endUser: String,
    val createdBy: String,
    val offerCheckin: String?,
    val offerCheckout: String?,
    val agencySourceExternalSystem: ExternalSystemEnum,
    val yachtId: Long,
    val yachtSlug: String,
    val yachtName: String,
    val modelName: String?,
    val manufacturerName: String?,
    val locationFromName: String,
    val locationFromCountry: String,
    val locationToName: String,
    val locationToCountry: String,
    val reservationDateFrom: LocalDateTime?,
    val reservationDateTo: LocalDateTime?,
    val agencyId: Long,
    val agencyName: String,
    val cancellationRequestAt: LocalDateTime?,
) : Serializable
