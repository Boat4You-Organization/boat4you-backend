package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.common.services.UnitCalculations.feetToMeters
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import java.math.BigDecimal
import java.time.LocalDate

data class YachtSearchParamObject(
    val locationIds: List<String>?,
    val charterTypes: List<CharterType>?,
    val vesselTypes: List<VesselType>?,
    val manufacturers: List<Long>?,
    val models: List<Long>?,
    val mainSailTypes: List<SailTypeEnum>?,
    val minBuildYear: Short?,
    val maxBuildYear: Short?,
    val minPersons: Short?,
    val maxPersons: Short?,
    val minCabins: Short?,
    val maxCabins: Short?,
    val minBerths: Short?,
    val maxBerths: Short?,
    val minLength: BigDecimal?,
    val maxLength: BigDecimal?,
    val minPrice: BigDecimal?,
    val maxPrice: BigDecimal?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val minWc: Short?,
    val maxWc: Short?,
    val minEnginePower: Short?,
    val maxEnginePower: Short?,
    val currency: CurrencyEnum,
    val amenities: List<Long>?,
    val services: List<Long>?,
    val yachtIds: List<Long>?,
    val language: LanguageEnum,
) {
    fun getMinLengthInMeters(): BigDecimal? {
        if (minLength == null) {
            return null
        }
        return if (language == LanguageEnum.EN) {
            feetToMeters(minLength)
        } else {
            minLength
        }
    }

    fun getMaxLengthInMeters(): BigDecimal? {
        if (maxLength == null) {
            return null
        }
        return if (language == LanguageEnum.EN) {
            feetToMeters(maxLength)
        } else {
            maxLength
        }
    }

    fun getMinPriceInEur(calc: ExchangeRateCalculationService): BigDecimal? {
        return if (minPrice == null) {
            return null
        } else {
            calc.toEur(minPrice, currency)
        }
    }

    fun getMaxPriceInEur(calc: ExchangeRateCalculationService): BigDecimal? {
        return if (maxPrice == null) {
            return null
        } else {
            calc.toEur(maxPrice, currency)
        }
    }
}
