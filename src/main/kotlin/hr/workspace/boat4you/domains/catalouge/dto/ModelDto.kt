package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ModelDto(
    @get:JsonProperty("id")
    val id: kotlin.Long?,
    @get:JsonProperty("name")
    val name: kotlin.String?,
)
