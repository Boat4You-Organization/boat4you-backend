package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty
import hr.workspace.boat4you.domains.catalouge.enums.LocationType

data class LocationViewDto(
    @get:JsonProperty("id")
    val id: kotlin.String?,
    @get:JsonProperty("realId")
    val realId: kotlin.Long?,
    @get:JsonProperty("name")
    val name: kotlin.String?,
    @get:JsonProperty("locationType")
    val locationType: LocationType? = null,
    @get:JsonProperty("countryCode")
    val countryCode: String? = null,
)
