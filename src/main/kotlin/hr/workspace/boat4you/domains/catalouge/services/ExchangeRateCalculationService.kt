package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.PriceInfoDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRateRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Service
class ExchangeRateCalculationService(
    private val exchangeRateRepository: ExchangeRateRepository,
) {
    fun calculatePriceInfo(
        price: BigDecimal?,
        currency: CurrencyEnum?,
    ): PriceInfoDto? {
        if (price == null) {
            return null
        }
        if (currency == null) {
            return null
        }
        if (currency == CurrencyEnum.EUR) {
            return PriceInfoDto(
                amount = price,
                currency = CurrencyEnum.EUR.value,
                validAt = LocalDate.now(),
                rate = BigDecimal.ONE,
            )
        }

        return calculateInCurrency(price, currency)
    }

    private fun calculateInCurrency(
        amountInEur: BigDecimal,
        currency: CurrencyEnum,
    ): PriceInfoDto? {
        val exchangeRate = exchangeRateRepository.findLatestByCurrency(currency.name) ?: return null

        return PriceInfoDto(
            amount = amountInEur.multiply(exchangeRate.rate),
            currency = exchangeRate.currency!!,
            validAt = exchangeRate.validAt!!,
            rate = exchangeRate.rate!!,
        )
    }

    fun toEur(
        amount: BigDecimal?,
        currency: CurrencyEnum,
    ): BigDecimal? {
        if (currency == CurrencyEnum.EUR) {
            return amount
        }
        val exchangeRate = exchangeRateRepository.findLatestByCurrency(currency.name) ?: return null
        return amount?.divide(exchangeRate.rate, 2, RoundingMode.HALF_UP)
    }
}
