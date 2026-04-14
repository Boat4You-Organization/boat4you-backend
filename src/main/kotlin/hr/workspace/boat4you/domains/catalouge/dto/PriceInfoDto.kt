package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import java.math.BigDecimal
import java.time.LocalDate

data class PriceInfoDto(
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val amount: BigDecimal,
    val currency: String,
    val validAt: LocalDate,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val rate: BigDecimal,
)
