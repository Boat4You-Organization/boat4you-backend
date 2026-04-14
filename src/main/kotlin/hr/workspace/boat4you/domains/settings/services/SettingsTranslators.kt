package hr.workspace.boat4you.domains.settings.services

import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.jpa.SettingsEntity

fun SettingsEntity.toDto(): SettingsDto =
    SettingsDto(
        name = this.name,
        value = this.value,
    )
