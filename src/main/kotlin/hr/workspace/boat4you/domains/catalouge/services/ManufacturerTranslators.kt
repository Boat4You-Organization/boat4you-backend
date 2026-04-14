package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.ManufacturerDto
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer

fun Manufacturer.toDto(): ManufacturerDto =
    ManufacturerDto(
        id = id,
        name = name,
    )
