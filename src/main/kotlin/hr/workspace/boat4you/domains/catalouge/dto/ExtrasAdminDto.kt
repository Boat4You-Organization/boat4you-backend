package hr.workspace.boat4you.domains.catalouge.dto

data class ExtrasAdminDto(
    val id: Long,
    val labelCode: String,
    val filterOrder: Short?,
    val matchKeys: String?,
)
