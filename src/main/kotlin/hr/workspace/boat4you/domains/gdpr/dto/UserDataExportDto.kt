package hr.workspace.boat4you.domains.gdpr.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Article 20 (Data Portability) export payload — everything we hold about
 * the customer in a structured, machine-readable JSON form. Returned with
 * `Content-Disposition: attachment; filename=...` so browsers download it.
 *
 * Versioned via `formatVersion` so future schema changes don't break clients
 * that already saved an older export.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserDataExportDto(
    val formatVersion: String = "1.0",
    val exportedAt: Instant,
    val user: ExportedUserDto,
    val reservations: List<ExportedReservationDto>,
    val customOffers: List<ExportedCustomOfferDto>,
    val inquiries: List<ExportedInquiryDto>,
    val gdprActivityLog: List<ExportedGdprActivityDto>,
    /** Card payments from the secret /pay link made with the user's e-mail (no user FK; matched by e-mail). */
    val customPayments: List<ExportedCustomPaymentDto> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedUserDto(
    val id: Long,
    val name: String?,
    val surname: String?,
    val email: String?,
    val phoneNumber: String?,
    val address: String?,
    val city: String?,
    val country: String?,
    val language: String?,
    val currency: String?,
    val registrationStatus: String?,
    val createdAt: Instant?,
    val deletedAt: Instant?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedReservationDto(
    val reservationId: Long,
    val reservationNumber: String?,
    val status: String?,
    val sysStatus: String?,
    val externalCode: String?,
    val createdAt: LocalDateTime?,
    val dateFrom: LocalDateTime?,
    val dateTo: LocalDateTime?,
    val yachtName: String?,
    val yachtModel: String?,
    val locationFrom: String?,
    val locationTo: String?,
    val basePrice: BigDecimal?,
    val totalPrice: BigDecimal?,
    val clientPrice: BigDecimal?,
    val deposit: BigDecimal?,
    val currency: String?,
    val paymentNote: String?,
    val cancellationRequest: String?,
    val cancellationRequestAt: LocalDateTime?,
    val optionExpiresAt: LocalDateTime?,
    val paymentPhases: List<ExportedPaymentPhaseDto>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedPaymentPhaseDto(
    val deadline: LocalDate?,
    val amount: BigDecimal?,
    val paidOn: Instant?,
    val stripePaymentIntentId: String?,
    val paymentProvider: String? = null,
    val providerPaymentId: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedCustomOfferDto(
    val id: Long,
    val createdAt: LocalDateTime?,
    val status: String?,
    val totalPrice: BigDecimal?,
    val currency: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedInquiryDto(
    val id: Long,
    val createdAt: LocalDateTime?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val yachtName: String?,
    val message: String?,
    val status: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedGdprActivityDto(
    val action: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val notes: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExportedCustomPaymentDto(
    val id: Long,
    val site: String,
    val reference: String,
    val amountMinor: Long,
    val refundedAmountMinor: Long,
    val currency: String,
    val status: String,
    val payerEmail: String,
    val locale: String,
    val clientIp: String?,
    val termsVersion: String,
    val acceptedAt: Instant,
    val createdAt: Instant,
    val completedAt: Instant?,
)
