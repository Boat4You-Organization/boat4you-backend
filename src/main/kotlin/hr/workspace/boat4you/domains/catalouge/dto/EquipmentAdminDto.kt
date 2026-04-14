package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum

data class EquipmentAdminDto(
    val id: Long,
    val labelCode: String,
    val category: CategoryEnum,
    val filterOrder: Short?,
    val matchKeys: String?,
)
