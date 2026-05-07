package hr.workspace.boat4you.domains.reservation.jpa

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Mapping for DB view
 */
@Entity
@Immutable
@Table(name = "reservation_view")
open class ReservationView protected constructor() {
    @Id
    @Column(name = "reservation_id")
    open var reservationId: Long? = null
        protected set

    @Column(name = "reservation_flow_id")
    open var reservationFlowId: Long? = null
        protected set

    @Enumerated
    @Column(name = "reservation_status")
    open var reservationStatus: OfferStatus? = null
        protected set

    @Enumerated
    @Column(name = "reservation_sys_status")
    open var reservationSysStatus: ReservationStatus? = null
        protected set

    @Column(name = "reservation_created_at")
    open var reservationCreatedAt: LocalDateTime? = null
        protected set

    @Column(name = "reservation_option_expires_at")
    open var reservationOptionExpiresAt: LocalDateTime? = null
        protected set

    @Column(name = "reservation_total_price")
    open var reservationTotalPrice: BigDecimal? = null
        protected set

    @Column(name = "reservation_discount")
    open var reservationDiscount: BigDecimal? = null
        protected set

    @Column(name = "reservation_client_price")
    open var reservationClientPrice: BigDecimal? = null
        protected set

    @Column(name = "reservation_external_id")
    open var reservationExternalId: Long? = null
        protected set

    @Size(max = 100)
    @Column(name = "reservation_external_reservation_code", length = 100)
    open var reservationExternalReservationCode: String? = null
        protected set

    @Size(max = 9)
    @Column(name = "reservation_number", length = 9)
    open var reservationNumber: String? = null
        protected set

    @Size(max = 500)
    @Column(name = "reservation_note", length = 500)
    open var reservationNote: String? = null
        protected set

    @Size(max = 500)
    @Column(name = "reservation_payment_note", length = 500)
    open var reservationPaymentNote: String? = null
        protected set

    @Size(max = 1000)
    @Column(name = "reservation_crew_list_url", length = 1000)
    open var reservationCrewListUrl: String? = null
        protected set

    @Column(name = "reservation_user_id")
    open var reservationUserId: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "reservation_flow_name")
    open var reservationFlowName: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "reservation_flow_surname")
    open var reservationFlowSurname: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "reservation_flow_email")
    open var reservationFlowEmail: String? = null
        protected set

    @Size(max = 30)
    @Column(name = "reservation_flow_phone", length = 30)
    open var reservationFlowPhone: String? = null
        protected set

    @Size(max = 1000)
    @Column(name = "reservation_flow_request", length = 1000)
    open var reservationFlowRequest: String? = null
        protected set

    @Column(name = "offer_id")
    open var offerId: Long? = null
        protected set

    @Column(name = "reservation_flow_status")
    open var reservationFlowStatus: Short? = null
        protected set

    @Column(name = "offer_date_from")
    open var offerDateFrom: LocalDate? = null
        protected set

    @Column(name = "offer_date_to")
    open var offerDateTo: LocalDate? = null
        protected set

    @Size(max = 20)
    @Column(name = "offer_checkin", length = 20)
    open var offerCheckin: String? = null
        protected set

    @Size(max = 20)
    @Column(name = "offer_checkout", length = 20)
    open var offerCheckout: String? = null
        protected set

    @Column(name = "agency_source_external_system_id")
    open var agencySourceExternalSystemId: Int? = null
        protected set

    @Column(name = "yacht_id")
    open var yachtId: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "yacht_name")
    open var yachtName: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "model_name")
    open var modelName: String? = null
        protected set

    @Column(name = "yacht_main_image")
    open var yachtMainImage: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "manufacturer_name")
    open var manufacturerName: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "location_from_name")
    open var locationFromName: String? = null
        protected set

    @Size(max = 2)
    @Column(name = "location_from_country", length = 2)
    open var locationFromCountry: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "location_to_name")
    open var locationToName: String? = null
        protected set

    @Size(max = 2)
    @Column(name = "location_to_country", length = 2)
    open var locationToCountry: String? = null
        protected set

    @Column(name = "reservation_date_from")
    open var reservationDateFrom: LocalDateTime? = null
        protected set

    @Column(name = "reservation_date_to")
    open var reservationDateTo: LocalDateTime? = null
        protected set

    @Column(name = "created_by_id")
    open var createdById: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "created_by_name")
    open var createdByName: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "created_by_surname")
    open var createdBySurname: String? = null
        protected set

    @Column(name = "created_for_id")
    open var createdForId: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "created_for_name")
    open var createdForName: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "created_for_surname")
    open var createdForSurname: String? = null
        protected set

    @Size(max = 30)
    @Column(name = "reservation_external_status", length = 30)
    open var reservationExternalStatus: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "created_by_email")
    open var createdByEmail: String? = null
        protected set

    @Column(name = "agency_id")
    open var agencyId: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "agency_name")
    open var agencyName: String? = null
        protected set

    @Size(max = 150)
    @Column(name = "agency_email", length = 150)
    open var agencyEmail: String? = null
        protected set

    @Size(max = 200)
    @Column(name = "agency_phone", length = 200)
    open var agencyPhone: String? = null
        protected set

    @Size(max = 150)
    @Column(name = "agency_city", length = 150)
    open var agencyCity: String? = null
        protected set

    @Size(max = 255)
    @Column(name = "agency_address", length = 255)
    open var agencyAddress: String? = null
        protected set

    @Size(max = 30)
    @Column(name = "agency_zip", length = 30)
    open var agencyZip: String? = null
        protected set

    @Size(max = 100)
    @Column(name = "agency_country", length = 100)
    open var agencyCountry: String? = null
        protected set

    @Size(max = 100)
    @Column(name = "agency_vat_code", length = 100)
    open var agencyVatCode: String? = null
        protected set

    @Column(name = "reservation_cancelation_request_at")
    open var reservationCancelationRequestAt: LocalDateTime? = null
        protected set

    @Size(max = 1000)
    @Column(name = "reservation_cancelation_request", length = 1000)
    open var reservationCancelationRequest: String? = null
        protected set

    @Column(name = "reservation_cancelation_rejected_at")
    open var reservationCancelationRejectedAt: LocalDateTime? = null
        protected set

    @Column(name = "reservation_cancelation_rejected_reason", columnDefinition = "TEXT")
    open var reservationCancelationRejectedReason: String? = null
        protected set

    @Column(name = "calculated_total_price")
    open var calculatedTotalPrice: BigDecimal? = null
        protected set

    @Column(name = "offer_client_price")
    open var offerClientPrice: BigDecimal? = null
        protected set

    // External base price from Nausys/MMK — the "list price" shown before the
    // agency discount. Used on /my-bookings to display the strike-through
    // original price next to the discounted total.
    @Column(name = "offer_list_price")
    open var offerListPrice: BigDecimal? = null
        protected set

    // What we owe the charter agency (Nausys `agencyPrice`, MMK `finalPrice`).
    // Admin-only — not exposed on customer endpoints.
    @Column(name = "reservation_agency_price")
    open var reservationAgencyPrice: BigDecimal? = null
        protected set

    // Our commission on this booking (client price minus agency price minus
    // any additional broker discount we absorbed). Admin-only.
    @Column(name = "reservation_commission")
    open var reservationCommission: BigDecimal? = null
        protected set

    // Free-form admin notes (internal support memos). Admin-only.
    @Column(name = "reservation_admin_notes", columnDefinition = "TEXT")
    open var reservationAdminNotes: String? = null
        protected set

    @Enumerated
    @Column(name = "charter_type")
    open var charterType: CharterType? = null
        protected set

    fun numberOfDays(): Long {
        return ChronoUnit.DAYS
            .between(reservationDateFrom!!.toLocalDate(), reservationDateTo!!.toLocalDate())
            .coerceAtLeast(1)
    }

    /**
     * Effective per-day client price. Uses the offer's price when present,
     * falls back to the reservation's own price for admin "fictitious"
     * replacement reservations (no offer row). Returns zero if both are
     * somehow null — defensive, but shouldn't happen in practice.
     */
    fun offerClientPricePerDay(): BigDecimal {
        val base = offerClientPrice ?: reservationClientPrice ?: BigDecimal.ZERO
        return base.divide(numberOfDays().toBigDecimal(), 2, RoundingMode.HALF_UP)
    }

    /**
     * Effective total client price. Prefers the offer's figure (which the
     * customer booked against), falls back to the reservation's own price
     * for fictitious rows where no offer exists.
     */
    fun effectiveClientPrice(): BigDecimal =
        offerClientPrice ?: reservationClientPrice ?: BigDecimal.ZERO
}
