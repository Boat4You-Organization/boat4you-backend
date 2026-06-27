package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import java.time.LocalDateTime

/**
 * Option-honesty (Deploy 4 step 2; extended 2026-06-27): a hold whose option expiry has
 * lapsed is bookable again, so it surfaces as FREE at read time — even before the periodic
 * partner sync rewrites or the purge deletes the row. Two cases release to FREE:
 *   - ANY row with a NON-NULL, already-past optionExpiration (a lapsed OPTION, or a
 *     "zombie" mis-statused as RESERVATION/SERVICE that still carries a stale expired
 *     hold), and
 *   - an expiry-less OPTION (an unconditional soft hold).
 * A genuine RESERVATION / SERVICE has optionExpiration = NULL and passes through unchanged.
 */
fun ExternalReservation.toYachtAvailabilityDto(now: LocalDateTime = LocalDateTime.now()): YachtAvailabilityDto {
    val expiry = optionExpiration
    val effective =
        when {
            expiry != null && expiry.isBefore(now) -> ExternalReservationStatus.FREE
            status == ExternalReservationStatus.OPTION && expiry == null -> ExternalReservationStatus.FREE
            else -> status
        }
    return YachtAvailabilityDto(from = dateFrom, to = dateTo, status = effective)
}
