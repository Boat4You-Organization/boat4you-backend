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
        primarySource = ExternalSystemEnum.fromValue(agencySources.firstOrNull { it.primary == true }?.externalSystem?.id),
    )

fun Agency.updateBlockWithModel(model: AgencyDto) {
    discount = model.discount
    skipExternalSystem = model.skipExternalSystem
}
