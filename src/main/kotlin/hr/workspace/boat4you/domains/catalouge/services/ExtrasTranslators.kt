package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.ExtrasAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasDto
import hr.workspace.boat4you.domains.catalouge.jpa.Extra

fun Extra.toDto(): ExtrasDto =
    ExtrasDto(
        id = id!!,
        labelCode = labelCode!!,
        filterOrder = filterOrder,
    )

fun Extra.toAdminDto(): ExtrasAdminDto =
    ExtrasAdminDto(
        id = id!!,
        labelCode = labelCode!!,
        filterOrder = filterOrder,
        matchKeys = matchKeys,
    )
