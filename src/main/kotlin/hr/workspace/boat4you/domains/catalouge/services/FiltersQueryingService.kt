package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.FiltersDto
import hr.workspace.boat4you.domains.catalouge.dto.MeasurementUnitDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceInfoDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.jpa.FiltersViewRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

@Service
class FiltersQueryingService(
    private val repository: FiltersViewRepository,
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
) {
    fun getFilters(
        currency: CurrencyEnum,
        language: LanguageEnum,
    ): FiltersDto? {
        val filter = repository.findById(1).get()
        val defaultMinPriceInfo =
            PriceInfoDto(
                amount = BigDecimal.ZERO,
                currency = currency.value,
                validAt = LocalDate.now(),
                rate = BigDecimal.ONE,
            )
        val defaultMaxPriceInfo =
            PriceInfoDto(
                amount = BigDecimal.valueOf(10000),
                currency = currency.value,
                validAt = LocalDate.now(),
                rate = BigDecimal.ONE,
            )

        val filtersDto =
            FiltersDto(
                minPrice =
                    exchangeRateCalculationService.calculatePriceInfo(
                        secureMinValue(filter.minPrice),
                        currency,
                    ) ?: defaultMinPriceInfo,
                maxPrice =
                    exchangeRateCalculationService.calculatePriceInfo(
                        filter.maxPrice ?: BigDecimal.valueOf(10000),
                        currency,
                    ) ?: defaultMaxPriceInfo,
                minCabins = secureMinValue(filter.minCabins),
                maxCabins = filter.maxCabins ?: 10,
                minPersons = secureMinValue(filter.minPersons),
                maxPersons = filter.maxPersons ?: 20,
                minBerths = secureMinValue(filter.minBerths),
                maxBerths = filter.maxBerths ?: 20,
                minLength =
                    MeasurementUnitDto.toDto(
                        secureMinValue(filter.minLenght),
                        language,
                    ),
                maxLength =
                    MeasurementUnitDto.toDto(
                        filter.maxLenght,
                        language,
                    ),
                minYear = secureMinValue(filter.minBuildYear, 1900),
                maxYear = filter.maxBuildYear ?: LocalDate.now().year.toShort(), // buffer overflow in year 32767
                minWc = secureMinValue(filter.minWc),
                maxWc = filter.maxWc ?: 20,
                minEnginePower = secureMinValue(filter.minWc),
                maxEnginePower = filter.maxEnginePower ?: 5000,
            )
        return filtersDto
    }

    private fun secureMinValue(
        value: Short?,
        defaultValue: Short? = null,
    ): Short {
        if (value == null) {
            return defaultValue ?: 0
        }
        if (value < 0) {
            return defaultValue ?: 0
        }
        return value
    }

    private fun secureMinValue(
        value: BigDecimal?,
        defaultValue: BigDecimal? = null,
    ): BigDecimal {
        if (value == null) {
            return defaultValue ?: BigDecimal.ZERO
        }
        if (value < BigDecimal.ZERO) {
            return defaultValue ?: BigDecimal.ZERO
        }
        return value
    }
}
