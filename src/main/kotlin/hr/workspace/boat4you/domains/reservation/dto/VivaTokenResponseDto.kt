package hr.workspace.boat4you.domains.reservation.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class VivaTokenResponseDto(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("token_type") val tokenType: String,
    @get:JsonProperty("expires_in") val expiresIn: Long,
    val scope: String? = null,
)
