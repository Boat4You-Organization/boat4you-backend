package hr.workspace.boat4you.common.services

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object PriceCalculations {
    fun calculateClientPrice(
        basePrice: BigDecimal,
        agencyDiscount: BigDecimal,
        applyDiscount: Boolean,
    ): BigDecimal {
        return if (applyDiscount) {
            basePrice.multiply(BigDecimal.ONE.subtract(agencyDiscount.divide(BigDecimal(100))))
        } else {
            basePrice
        }
    }

    fun calculateExtrasPrice(
        extrasPrice: BigDecimal,
        unit: ExtrasUnitType,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        persons: Int?,
    ): Result<BigDecimal> {
        return when (unit) {
            ExtrasUnitType.PER_WEEK -> {
                val weeksBetween = ChronoUnit.WEEKS.between(dateFrom, dateTo).coerceAtLeast(1)
//                val remainingDays = ChronoUnit.DAYS.between(dateFrom.plusWeeks(weeksBetween), dateTo)
//                val interval = (weeksBetween + if (remainingDays > 0) 1 else 0).coerceAtLeast(1).toInt()

                Result.success(extrasPrice.multiply(BigDecimal(weeksBetween)))
            }

            ExtrasUnitType.PER_WEEK_PERSON -> {
                if (persons == null || persons < 1) {
                    Result.failure(IllegalArgumentException("Persons must be between 1 and $persons"))
                } else {
                    val weeksBetween = ChronoUnit.WEEKS.between(dateFrom, dateTo).coerceAtLeast(1)
                    Result.success(extrasPrice.multiply(BigDecimal(weeksBetween)).multiply(BigDecimal(persons)))
                }
            }

            ExtrasUnitType.PER_NIGHT -> {
                val daysBetween = ChronoUnit.DAYS.between(dateFrom, dateTo).coerceAtLeast(1)
                Result.success(extrasPrice.multiply(BigDecimal(daysBetween)))
            }

            ExtrasUnitType.PER_NIGHT_PERSON -> {
                if (persons == null || persons < 1) {
                    Result.failure(IllegalArgumentException("Persons must be between 1 and $persons"))
                } else {
                    val daysBetween = ChronoUnit.DAYS.between(dateFrom, dateTo).coerceAtLeast(1)
                    Result.success(extrasPrice.multiply(BigDecimal(daysBetween)).multiply(BigDecimal(persons)))
                }
            }

            ExtrasUnitType.PER_BOOKING, ExtrasUnitType.PER_BOAT, ExtrasUnitType.AMOUNT -> {
                Result.success(extrasPrice)
            }

            ExtrasUnitType.PER_BOOKING_PERSON -> {
                if (persons == null || persons < 1) {
                    return Result.failure(IllegalArgumentException("Persons must be between 1 and $persons"))
                }
                Result.success(extrasPrice.multiply(BigDecimal(persons)))
            }

            else -> Result.failure(IllegalArgumentException("Unsupported unit: $unit"))
        }
    }
}
