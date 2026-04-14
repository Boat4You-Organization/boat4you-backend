package hr.workspace.boat4you.domains.reservation.dto

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum

data class UserExtReservationDto(
    val email: String,
    val externalSystem: ExternalSystemEnum,
    val externalId: Long,
    val previousReservationFlowId: Long?,
)
