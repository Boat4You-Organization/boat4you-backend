package hr.workspace.boat4you.domains.reservation.model

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.reservation.enums.QuantityUnit
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReservationResponseWrapper(
    val externalId: Long,
    val externalCode: String,
    val dateFrom: LocalDateTime,
    val dateTo: LocalDateTime,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val product: CharterType,
    val locationFromId: Long,
    val locationToId: Long,
    val locationFrom: Location,
    val locationTo: Location,
    val status: OfferStatus,
    val externalStatus: String?,
    val basePrice: BigDecimal,
    val discount: BigDecimal,
    val commission: BigDecimal?,
    val totalPrice: BigDecimal,
    val clientPrice: BigDecimal,
    val deposit: BigDecimal?,
    val currency: String,
    val paymentNote: String?,
    val bankDetails: String?,
    val note: String?,
    val extras: List<ExtraWrapper>?,
    val paymentPlan: List<PaymentPlanWrapper>?,
    val responseBody: String?,
    val crewListUrl: String?,
    val yachtId: Long,
    val calculatedSysStatus: ReservationStatus,
)

data class ExtraWrapper(
    val externalId: Long,
    val name: String?,
    val quantity: Float?,
    val unit: QuantityUnit?,
    val price: BigDecimal?,
    val payableInBase: Boolean?,
)

data class PaymentPlanWrapper(
    val date: LocalDate,
    val amount: BigDecimal,
)
