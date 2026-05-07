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
     * Sitemap-only filter — restrict the yacht list to a 2-letter ISO
     * country whitelist (BS / ES / FR / GR / HR / IT / ME / MQ / SC / TR /
     * VG / GD as of 4.5.2026). Used by /sitemap-yachts so Google only
     * crawls yachts in countries we actively promote, while the customer
     * search keeps showing every yacht regardless of country (deep-links
     * to non-promoted countries still resolve normally).
     *
     * yacht_search_view stores the country code as the last 2 chars of
     * `location_full_name` (format: "<id>-<name>-<countryCode>"). The
     * predicate uses RIGHT() rather than a JOIN to avoid the JPA
     * fetch-join + Pageable totalElements drift documented in
     * project_jpa_join_fetch_pagination memory.
     */
    val countryCodes: List<String>? = null,
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
