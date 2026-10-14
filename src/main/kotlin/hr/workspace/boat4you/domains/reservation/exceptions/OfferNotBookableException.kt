package hr.workspace.boat4you.domains.reservation.exceptions

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import java.time.LocalDate

/**
 * WS-C1 (`BOOKING_C1=ON`): the customer tried to book a week that is no longer FREE in our offer table, or that a
 * live partner RESERVATION / SERVICE overlaps. Served as 409 / 3006 ("send an inquiry") instead of the catch-all 500.
 *
 * Carries the period and the partner system, resolved while `createReservationFlow` still had its transaction, so
 * the controller can queue exactly that period for a partner re-check without touching lazy entities.
 */
class OfferNotBookableException(
    val offerId: Long?,
    val yachtId: Long?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val externalSystem: ExternalSystemEnum?,
    reason: String,
) : RuntimeException("Offer $offerId is not available for reservation: $reason")
