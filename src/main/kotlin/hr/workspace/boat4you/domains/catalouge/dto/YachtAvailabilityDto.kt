package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import java.time.LocalDate

data class YachtAvailabilityDto(
    @get:JsonProperty("from")
    val from: LocalDate?,
    @get:JsonProperty("to")
    val to: LocalDate?,
    @get:JsonProperty("status")
    val status: ExternalReservationStatus?,
)
