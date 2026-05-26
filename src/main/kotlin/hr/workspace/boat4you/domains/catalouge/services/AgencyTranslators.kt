package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.AgencyDto
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum

fun Agency.toDto(): AgencyDto =
    AgencyDto(
        id = id,
        name = name,
        email = email,
        address = address,
        city = city,
        country = country,
        zip = zip,
        vatCode = vatCode,
        web = web,
        phone = phone,
        mobile = mobile,
        iban = iban,
        active = active,
        discount = discount,
        director = director,
        skipExternalSystem = skipExternalSystem,
        recommended = recommended,
        primarySource = ExternalSystemEnum.fromValue(agencySources.firstOrNull { it.primary == true }?.externalSystem?.id),
        sources = agencySources.mapNotNull { ExternalSystemEnum.fromValue(it.externalSystem?.id) },
    )

fun Agency.updateBlockWithModel(model: AgencyDto) {
    discount = model.discount
    skipExternalSystem = model.skipExternalSystem
    // Null-coalesce so a payload that omits the field (older clients, partial
    // forms) doesn't accidentally clear the flag. Treat absence as "no change".
    model.recommended?.let { recommended = it }
}
