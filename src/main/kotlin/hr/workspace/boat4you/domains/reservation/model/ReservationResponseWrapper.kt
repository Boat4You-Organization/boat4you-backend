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
    /** Partner-system reservation ID. Null when [skipExternalSystem]
     *  is set on the agency — no partner call is made and the reservation
     *  is recorded with `external_id = null` (post V1_62 nullable). */
    val externalId: Long?,
    /** Partner-system human-readable reservation code (e.g. "RX-12345").
     *  Null on the skip-external-system path for the same reason as
     *  [externalId]. */
    val externalCode: String?,
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
    // What we owe the agency — Nausys `agencyPrice`, MMK `finalPrice`. Nullable
    // for DEV mock path that doesn't know the real partner-side figure.
    val agencyPrice: BigDecimal?,
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
) {
    /**
     * The partner code is not a display copy: NauSys gets it back as the reservation `uuid` on confirm (Stripe webhook,
     * after capture) and on cancel, so it may never be cut to fit `reservation.external_reservation_code`. A code we
     * cannot store is refused while the adapter can still release the option (observed max: 36 chars).
     */
    fun requireStorableCode(): ReservationResponseWrapper {
        check((externalCode?.length ?: 0) <= EXTERNAL_CODE_MAX_LENGTH) {
            "Partner reservation code is ${externalCode?.length} chars, we can store $EXTERNAL_CODE_MAX_LENGTH"
        }
        return this
    }

    companion object {
        /** Width of `reservation.external_reservation_code` (projected by reservation_view, so not simply widened). */
        const val EXTERNAL_CODE_MAX_LENGTH = 100
    }
}

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
