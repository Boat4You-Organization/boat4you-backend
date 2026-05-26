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
    /** Nullable — admin "fictitious" replacement reservations have no partner link. */
    val reservationExternalId: Long?,
    /** Nullable — admin "fictitious" replacement reservations have no partner link. */
    val reservationExternalReservationCode: String?,
    val reservationNumber: String?,
    val reservationUserId: Long?,
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
    // Nullable for fictitious admin-only replacement reservations whose
    // location may fall back to the yacht home or be missing entirely.
    val locationFromName: String?,
    val locationFromCountry: String?,
    val locationToName: String?,
    val locationToCountry: String?,
    val reservationDateFrom: LocalDateTime?,
    val reservationDateTo: LocalDateTime?,
    val agencyId: Long,
    val agencyName: String,
    val cancellationRequestAt: LocalDateTime?,
    val cancellationRejectedAt: LocalDateTime? = null,
    // Admin-only: what we owe the charter agency. Nullable for DEV mock and
    // legacy pre-V1_46 rows.
    val reservationAgencyPrice: BigDecimal?,
    // Admin-only: our commission on this booking.
    val reservationCommission: BigDecimal?,
    // Admin-only: internal notes (transfer info, callbacks, etc.).
    val reservationAdminNotes: String?,
) : Serializable
