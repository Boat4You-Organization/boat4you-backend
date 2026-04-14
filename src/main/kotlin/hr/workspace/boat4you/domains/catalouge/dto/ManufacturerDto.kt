package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ManufacturerDto(
    @get:JsonProperty("id")
    val id: Long?,
    @get:JsonProperty("name")
    val name: String?,
)
