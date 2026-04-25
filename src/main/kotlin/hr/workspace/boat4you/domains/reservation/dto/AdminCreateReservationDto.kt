package hr.workspace.boat4you.domains.reservation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Admin-initiated reservation. Used when the original yacht broke down / was
 * overbooked and the agency offers the customer a different boat. Flow:
 * cancel old reservation (keeps its own booking number + history) → admin
 * creates THIS as a brand-new reservation with a fresh booking number.
 * Mirrors how MMK / NauSYS handle the case.
 *
 * The admin is the price authority here: they type the total price (can be
 * lower or higher than catalogue) and explicitly lay out payment phases,
 * optionally marking the first phase PAID to carry over the installment the
 * customer already paid on the cancelled reservation.
 */
data class AdminCreateReservationDto(
    /** Existing customer the admin is assigning this reservation to. */
    @field:NotNull
    val userId: Long,
    /** Yacht picked from the admin's yacht search step. */
    @field:NotNull
    val yachtId: Long,
    /** Offer row that matches the chosen yacht + date window. Admin picks from search results. */
    @field:NotNull
    val offerId: Long,
    /**
     * Total price that overrides the offer's catalogue price. Admin may set any
     * positive amount — makes no assumption vs catalogue. Sanity-checked `> 0`.
     */
    @field:NotNull
    @field:Positive
    val totalPrice: BigDecimal,
    /**
     * Explicit per-phase breakdown. Sum of amounts MUST equal `totalPrice`
     * (service validates with 0.01 tolerance). `markPaid` on a phase means
     * the customer already paid it on the previous / cancelled reservation —
     * we record `paidOn = now` with no Stripe session id.
     */
    @field:NotEmpty
    @field:Valid
    val paymentPhases: List<AdminPhaseDto>,
    /** Free-text note shown to the admin (not to the customer). */
    val adminNotes: String? = null,
    /** Shown to the customer on `/my-bookings`. Also copied onto the reservation flow. */
    val specialRequest: String? = null,
    /** If true, we fire the option-created email. Default OFF so admin controls when the customer is notified. */
    val sendOptionEmail: Boolean = false,
)

data class AdminPhaseDto(
    @field:NotNull
    val deadline: LocalDate,
    @field:NotNull
    @field:Positive
    val amount: BigDecimal,
    /** When true, phase is stored with `paidOn = now` (carry-over from prior payment). */
    val markPaid: Boolean = false,
)
