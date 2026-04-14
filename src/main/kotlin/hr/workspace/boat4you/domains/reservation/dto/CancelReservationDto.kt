package hr.workspace.boat4you.domains.reservation.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class CancelReservationDto(
    @field:NotNull
    @field:Size(min = 10, max = 1000)
    val specialRequest: String,
)
