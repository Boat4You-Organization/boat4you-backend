package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum

data class EquipmentDto(
    val id: Long,
    val labelCode: String,
    val category: CategoryEnum,
    val filterOrder: Short?,
)
