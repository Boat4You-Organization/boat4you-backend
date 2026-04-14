package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import java.math.BigDecimal

data class YachtExtrasDto(
    val id: Long,
    val name: String?,
    val payableInBase: Boolean,
    val obligatory: Boolean,
    val priceEur: BigDecimal,
    val priceInfo: PriceInfoDto?,
    val unit: ExtrasUnitType?,
    val extras: ExtrasDto?,
    val key: String,
    val isStartingPrice: Boolean?,
)
