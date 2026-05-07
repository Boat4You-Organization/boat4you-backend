package hr.workspace.boat4you.domains.reservation.dto

import java.time.Instant

/**
 * List-view metadata for an admin-uploaded reservation attachment. The
 * heavyweight BYTEA payload is intentionally omitted — fetched lazily via
 * the dedicated download endpoint.
 */
data class ReservationDocumentDto(
    val id: Long,
    val reservationId: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: Long?,
    val uploadedAt: Instant,
    /** True = admin-only, hidden from customer my-bookings sidebar. */
    val isInternal: Boolean = false,
)
