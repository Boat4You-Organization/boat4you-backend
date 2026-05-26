package hr.workspace.boat4you.domains.reservation.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.MeasurementUnitDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceInfoDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtEquipmentDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtExtrasDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDocumentDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import java.math.BigDecimal
import java.time.LocalDateTime

data class MyReservationDetailsDto(
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
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val totalPrice: BigDecimal,
    val totalPriceInfo: PriceInfoDto?,
    val paymentPhases: List<PaymentPhaseDto>?,
    val yachtSlug: String,
    val selectedExtras: List<ExtrasPriceDto>,
    val yachtImages: List<YachtImageDto>?,
    val description: String?,
    val highlights: String?,
    val maxPersons: Short?,
    val cabins: Short?,
    val wc: Short?,
    val berths: Short?,
    val enginePower: Short?,
    val fuelTank: Int?,
    val waterTank: Int?,
    val beam: BigDecimal?,
    val mainSailType: SailTypeEnum?,
    val length: BigDecimal?,
    val yachtMainImage: Long?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val securityDeposit: BigDecimal?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val insuredSecurityDeposit: BigDecimal?,
    val depositCurrency: String?,
    val cancellationRequestAt: LocalDateTime?,
    val cancellationRequest: String?,
    val cancellationRejectedAt: LocalDateTime? = null,
    val cancellationRejectedReason: String? = null,
    val reservationNumber: String?,
    val agencyEmail: String?,
    val agencyPhone: String?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPriceEur: BigDecimal,
    val clientPriceInfo: PriceInfoDto?,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val clientPricePerDayEur: BigDecimal,
    val clientPricePerDayInfo: PriceInfoDto?,
    val numberOfDays: Short,
    val buildYear: Short?,
    val beamInfo: MeasurementUnitDto? = null,
    val lengthInfo: MeasurementUnitDto? = null,
    val crewNumber: Short?,
    val charterType: CharterType?,
    val vesselType: VesselType?,
    val manufacturerName: String?,
    val amenities: List<YachtEquipmentDto> = emptyList(),
    val specialRequest: String?,
    val services: List<YachtExtrasDto> = emptyList(),
    val obligatoryExtrasKeys: List<String> = emptyList(),
    // Crew list link from partner (Nausys/MMK at confirmation) OR manually
    // entered by admin in /admin/bookings/{id} when partner doesn't auto-fill
    // (fictitious bookings, manual confirmation flow). Customer renders as
    // "Open crew list" CTA in /my-bookings/{id}.
    val crewListUrl: String? = null,
    // Admin-uploaded files attached to the booking — pickup directions,
    // crew list docx, contract scans, anything PDF/DOC/DOCX. Mario rule
    // (3.5.2026): visible to customer in /my-bookings/{id}.
    val documents: List<ReservationDocumentDto> = emptyList(),
)
