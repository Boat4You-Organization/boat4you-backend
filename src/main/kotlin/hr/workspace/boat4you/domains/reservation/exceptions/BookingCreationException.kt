package hr.workspace.boat4you.domains.reservation.exceptions

/**
 * B2: thrown when the booking-creation orchestration (createReservationFlow ->
 * partner createOption -> createReservation) fails in a way that is NOT already
 * a known, cleanly-mapped domain exception (e.g. a raw IllegalState / NPE from
 * the reservation-persist step). The controller compensates first
 * (abandonFailedFlow reverts the offer and marks the flow ABANDONED), then
 * rethrows THIS so the customer gets a clear, leak-free error instead of the
 * catch-all 500 with a raw internal message.
 */
class BookingCreationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
