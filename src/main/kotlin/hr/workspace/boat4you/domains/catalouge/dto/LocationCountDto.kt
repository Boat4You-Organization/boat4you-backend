package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class LocationCountDto(
    @get:JsonProperty("id")
    val id: String,
    @get:JsonProperty("countryCode")
    val countryCode: String,
    @get:JsonProperty("yachtCount")
    val yachtCount: Int,
    @get:JsonProperty("name")
    val name: String?,
    @get:JsonProperty("continent")
    val continent: String?,
)
