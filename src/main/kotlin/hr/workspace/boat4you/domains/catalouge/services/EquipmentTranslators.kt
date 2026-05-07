package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.EquipmentAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.EquipmentDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtEquipmentDto
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment

fun Equipment.toDto(): EquipmentDto =
    EquipmentDto(
        id = id!!,
        labelCode = labelCode!!,
        category = category!!,
        filterOrder = filterOrder,
    )

fun Equipment.toAdminDto(): EquipmentAdminDto =
    EquipmentAdminDto(
        id = id!!,
        labelCode = labelCode!!,
        category = category!!,
        filterOrder = filterOrder,
        matchKeys = matchKeys,
    )

fun YachtEquipment.toDto(): YachtEquipmentDto =
    YachtEquipmentDto(
        id = id!!,
        name = name,
        equipment = equipment?.toDto(),
        highlight = highlight,
        quantity = quantity,
        comment = comment,
    )
