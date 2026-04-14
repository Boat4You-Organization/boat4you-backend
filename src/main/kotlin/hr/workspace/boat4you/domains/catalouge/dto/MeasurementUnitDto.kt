package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import hr.workspace.boat4you.common.services.TwoDecimalSerializer
import hr.workspace.boat4you.common.services.UnitCalculations
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.MeasurementUnit
import java.math.BigDecimal

data class MeasurementUnitDto(
    val unit: MeasurementUnit,
    @field:JsonSerialize(using = TwoDecimalSerializer::class)
    val amount: BigDecimal?,
) {
    companion object {
        fun toDto(
            amount: BigDecimal?,
            language: LanguageEnum,
        ): MeasurementUnitDto {
            return if (language == LanguageEnum.EN) {
                MeasurementUnitDto(
                    amount = if (amount != null) UnitCalculations.metersToFeet(amount) else null,
                    unit = MeasurementUnit.FEET,
                )
            } else {
                MeasurementUnitDto(
                    amount = amount,
                    unit = MeasurementUnit.METRE,
                )
            }
        }
    }
}
