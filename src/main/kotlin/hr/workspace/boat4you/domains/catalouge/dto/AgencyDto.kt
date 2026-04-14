package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class AgencyDto(
    @get:JsonProperty("id")
    val id: Long?,
    @get:JsonProperty("name")
    val name: String?,
    @get:JsonProperty("address")
    val address: String?,
    @get:JsonProperty("city")
    val city: String?,
    @get:JsonProperty("country")
    val country: String?,
    @get:JsonProperty("zip")
    val zip: String?,
    @get:JsonProperty("vatCode")
    val vatCode: String?,
    @get:JsonProperty("web")
    val web: String?,
    @get:JsonProperty("email")
    val email: String?,
    @get:JsonProperty("phone")
    val phone: String?,
    @get:JsonProperty("mobile")
    val mobile: String?,
    @get:JsonProperty("iban")
    val iban: String? = null,
    @get:JsonProperty("active")
    val active: Boolean? = null,
    @field:Min(value = 0)
    @field:Max(value = 100)
    @get:JsonProperty("discount")
    val discount: java.math.BigDecimal? = null,
    @get:JsonProperty("director")
    val director: String? = null,
    @get:JsonProperty("skipExternalSystem")
    val skipExternalSystem: Boolean? = null,
    @get:JsonProperty("primarySource")
    val primarySource: ExternalSystemEnum?,
)
