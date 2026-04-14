package hr.workspace.boat4you.domains.catalouge.dto

import java.math.BigDecimal

data class CustomYachtResponse(
    val id: Long,
    val name: String,
    val modelName: String?,
    val countryId: String,
    val countryName: String,
    val countryCode: String,
    val lowPrice: BigDecimal,
    val slug: String,
)
