package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.ExchangeRateSyncResponse
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRate
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRateRepository
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.LocalDate

@Service
class ExchangeRateService(
    private val exchangeRateRepository: ExchangeRateRepository,
    restTemplateBuilder: RestTemplateBuilder,
) {
    private val restTemplate: RestTemplate =
        restTemplateBuilder
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(15))
            .build()

    private val url =
        "https://api.frankfurter.app/latest?from=EUR&to=AUD,BGN,BRL,CAD,CHF,CNY,CZK,DKK,GBP,HKD,HUF,IDR,ILS,INR,ISK,JPY,KRW,MXN,MYR,NOK,NZD,PHP,PLN,RON,SEK,SGD,THB,TRY,USD,ZAR"

    @Transactional
    fun updateExchangeRates() {
        val response =
            restTemplate.getForObject(url, ExchangeRateSyncResponse::class.java)
                ?: throw RuntimeException("Failed to fetch exchange rates")

        val validAt = LocalDate.parse(response.date)

        val exchangeRates =
            response.rates.map { (currency, rate) ->
                ExchangeRate().apply {
                    this.validAt = validAt
                    this.currency = currency
                    this.rate = rate
                }
            }
        exchangeRateRepository.saveAll(exchangeRates)
    }
}
