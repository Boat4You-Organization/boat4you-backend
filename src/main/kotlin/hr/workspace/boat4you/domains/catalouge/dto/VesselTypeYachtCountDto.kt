package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty
import hr.workspace.boat4you.domains.catalouge.enums.VesselType

data class VesselTypeYachtCountDto(
    @get:JsonProperty("vesselType")
    val vesselType: VesselType?,
    @get:JsonProperty("yachtCount")
    val yachtCount: kotlin.Int?,
)
