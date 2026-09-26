package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
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
    /**
     * REGION only (26.9.2026 audit B01): the region's other known spellings — names it carried before its name became
     * canonical and the partners' current names ("Zadar region" for "Zadar"). A landing addressed by an alias is the
     * same place: resolve it to this row and redirect to the canonical name. Never another row's canonical name.
     */
    @get:JsonProperty("aliases")
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val aliases: List<String>? = null,
    /** MARINA only, internal: the same-place check of the dual-source merge (not part of the public payload). */
    @get:JsonIgnore
    val city: String? = null,
    @get:JsonIgnore
    val lat: Double? = null,
    @get:JsonIgnore
    val lon: Double? = null,
)
