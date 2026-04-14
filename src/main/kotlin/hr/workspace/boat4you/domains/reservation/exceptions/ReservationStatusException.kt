package hr.workspace.boat4you.domains.reservation.exceptions

import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus

class ReservationStatusException(
    val requiredStatus: ReservationStatus,
) : RuntimeException("Reservation status must be $requiredStatus")
