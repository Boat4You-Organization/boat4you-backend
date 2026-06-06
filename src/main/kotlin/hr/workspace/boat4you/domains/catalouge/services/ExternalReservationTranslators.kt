package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import java.time.LocalDateTime

/**
 * Option-honesty (Deploy 4 step 2): an OPTION whose hold has lapsed
 * (optionExpiration < now, or null) is bookable again, so it surfaces as FREE.
 * Lapsed options thus auto-release at read time even before the periodic
 * partner sync rewrites the row. RESERVATION / SERVICE / FREE pass through.
 */
fun ExternalReservation.toYachtAvailabilityDto(now: LocalDateTime = LocalDateTime.now()): YachtAvailabilityDto {
    val expiry = optionExpiration
    val effective =
        if (status == ExternalReservationStatus.OPTION && (expiry == null || expiry.isBefore(now))) {
            ExternalReservationStatus.FREE
        } else {
            status
        }
    return YachtAvailabilityDto(from = dateFrom, to = dateTo, status = effective)
}
