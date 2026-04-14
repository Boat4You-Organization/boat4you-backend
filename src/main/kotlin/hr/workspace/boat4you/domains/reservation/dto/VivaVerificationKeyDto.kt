package hr.workspace.boat4you.domains.reservation.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class VivaVerificationKeyDto(
    @get:JsonProperty("Key") val key: String,
)
