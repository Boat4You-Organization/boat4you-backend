package hr.workspace.boat4you.domains.settings.dto

import com.fasterxml.jackson.annotation.JsonProperty
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum

data class SettingsDto(
    @get:JsonProperty("name")
    val name: SettingsKeyEnum,
    @get:JsonProperty("value")
    val value: String? = null,
)
