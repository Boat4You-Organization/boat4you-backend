package hr.workspace.boat4you.domains.reservation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Admin-initiated "fictitious" reservation. Used when the agency moves a
 * customer onto a different yacht entirely outside our system (a broken-boat
 * swap they handle in Nausys/MMK directly) and the only thing our side has
 * to do is surface the new yacht in the customer's `/my-bookings` page.
 *
 * Unlike [AdminCreateReservationDto] this path:
 *   - does NOT require an `offerId` (the target yacht often has no offer row
 *     for the chosen week because the partner already sold that period);
 *   - does NOT call the partner API (`createExternalReservation`) — the
 *     agency has already recorded the booking on their side;
 *   - persists the Reservation directly with `sys_status = RESERVATION`,
 *     `external_id = null`, `external_status = null`. Sync jobs can't pull
 *     partner-side updates for this row (no link), so any further changes
 *     are admin-driven.
 *
 * Admin provides start/end dates, total price, and the explicit phase
 * breakdown (optionally marking phases paid to carry over customer payments
 * from the cancelled original reservation).
 */
data class CreateFictitiousReservationDto(
    /** Existing customer the admin is assigning this reservation to. */
    @field:NotNull
    val userId: Long,
    /** Target yacht (the replacement boat the agency moved the customer to). */
    @field:NotNull
    val yachtId: Long,
    /** Charter start date (admin-typed — does not come from an offer). */
    @field:NotNull
    val dateFrom: LocalDate,
    /** Charter end date (admin-typed — does not come from an offer). */
    @field:NotNull
    val dateTo: LocalDate,
    /** Total price the customer will see. Admin dictates — no catalogue lookup. */
    @field:NotNull
    @field:Positive
    val totalPrice: BigDecimal,
    /**
     * Explicit per-phase breakdown. Sum of amounts MUST equal `totalPrice`
     * (service validates with 0.01 tolerance). `markPaid` on a phase means
     * the customer already paid it on the previous reservation — we record
     * `paidOn = now` with no Stripe session id.
     */
    @field:NotEmpty
    @field:Valid
    val paymentPhases: List<AdminPhaseDto>,
    /** Free-text note visible only to admin (not shown to customer). */
    val adminNotes: String? = null,
    /** Shown to the customer on `/my-bookings`. */
    val specialRequest: String? = null,
)
