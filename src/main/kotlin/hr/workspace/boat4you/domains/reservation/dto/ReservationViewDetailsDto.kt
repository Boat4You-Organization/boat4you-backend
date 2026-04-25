package hr.workspace.boat4you.domains.reservation.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.MeasurementUnitDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtEquipmentDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * DTO for {@link hr.workspace.boat4you.domains.reservation.jpa.ReservationView}
 */
data class ReservationViewDetailsDto(
    val reservationId: Long?,
    val reservationFlowId: Long?,
    val reservationStatus: OfferStatus,
    val reservationSysStatus: ReservationStatus?,
    val reservationExternalStatus: String?,
    val reservationCreatedAt: LocalDateTime?,
    val reservationOptionExpiresAt: LocalDateTime?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val reservationTotalPrice: BigDecimal?,
    val reservationPaymentPhases: List<PaymentPhaseDto>?,
    val reservationDiscount: BigDecimal?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val reservationClientPrice: BigDecimal?,
    val reservationExternalId: Long?,
    val reservationExternalReservationCode: String?,
    val reservationNumber: String?,
    val reservationNote: String?,
    val reservationPaymentNote: String?,
    val reservationCrewListUrl: String?,
    val reservationUserId: Long?,
    val endUser: String,
    val endUserEmail: String,
    val endUserPhone: String?,
    val endUserRequest: String?,
    val createdBy: String,
    val createdByEmail: String,
    val checkin: String?,
    val checkout: String?,
    val agencySourceExternalSystem: ExternalSystemEnum?,
    val yachtId: Long?,
    val yachtSlug: String,
    val yachtName: String?,
    val modelName: String?,
    val yachtMainImage: Long?,
    val manufacturerName: String?,
    val locationFromName: String?,
    val locationFromCountry: String?,
    val locationToName: String?,
    val locationToCountry: String?,
    val reservationDateFrom: LocalDateTime?,
    val reservationDateTo: LocalDateTime?,
    val selectedExtras: List<ExtrasPriceDto>,
    val agencyId: Long,
    val agencyName: String,
    val agencyEmail: String,
    val agencyPhone: String?,
    val cancellationRequestAt: LocalDateTime?,
    val cancellationRequest: String?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val securityDeposit: BigDecimal?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val insuredSecurityDeposit: BigDecimal?,
    val depositCurrency: String?,
    val buildYear: Short?,
    val beamInfo: MeasurementUnitDto? = null,
    val lengthInfo: MeasurementUnitDto? = null,
    val crewNumber: Short?,
    val charterType: CharterType?,
    val vesselType: VesselType?,
    val amenities: List<YachtEquipmentDto> = emptyList(),
    val specialRequest: String?,
    // Free-form admin notes (internal). Admin-only — not exposed in customer
    // reservation endpoints.
    val adminNotes: String?,
) : Serializable
