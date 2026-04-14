package hr.workspace.boat4you.domains.catalouge.dto

data class YachtEquipmentDto(
    val id: Long,
    val name: String?,
    val equipment: EquipmentDto?,
)
