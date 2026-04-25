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
    /**
     * Admin-only filter — restrict search to yachts operated by specific
     * agencies (charter companies). Customer-facing search does not use this;
     * the param flows through unchanged because filtering happens in
     * YachtQueryingService by `agencyId` column on YachtSearchView.
     */
    val agencyIds: List<Long>? = null,
    /**
     * Admin-only "replacement flow" flag — when true, the search ignores
     * offer availability (UNAVAILABLE / RESERVED / OPTION) and returns every
     * yacht that otherwise matches the filters. Used when the agency has
     * already re-booked a different yacht for the same customer outside our
     * system — the yacht is UNAVAILABLE in our sync cache but the admin still
     * needs to create our-side reservation against it.
     */
    val includeUnavailable: Boolean = false,
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
