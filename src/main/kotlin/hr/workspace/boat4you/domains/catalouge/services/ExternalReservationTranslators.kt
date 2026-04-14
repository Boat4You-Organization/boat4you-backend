package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation

fun ExternalReservation.toYachtAvailabilityDto(): YachtAvailabilityDto =
    YachtAvailabilityDto(
        from = dateFrom,
        to = dateTo,
        status = status,
    )
