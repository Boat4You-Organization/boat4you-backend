package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.SimpleOfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.toDto
import org.springframework.stereotype.Component

@Component
class OfferMapper(
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
    private val yachtExtrasMapper: YachtExtrasMapper,
) {
    fun toDto(
        offer: Offer,
        currency: CurrencyEnum?,
    ): OfferDto {
        val pricePerDayEur = offer.pricePerDayEur()
        val filteredOfferExtras = offer.filterDuplicateExtras()
        return OfferDto(
            id = offer.id,
            dateFrom = offer.dateFrom,
            dateTo = offer.dateTo,
            clientPriceEur = offer.clientPrice,
            totalPriceEur = offer.totalPrice,
            totalDiscountEur = offer.totalDiscount,
            totalDiscountInfo = exchangeRateCalculationService.calculatePriceInfo(offer.totalDiscount, currency),
            clientPriceInfo = exchangeRateCalculationService.calculatePriceInfo(offer.clientPrice, currency),
            totalPriceCalcInfo = exchangeRateCalculationService.calculatePriceInfo(offer.totalPrice, currency),
            status = SimpleOfferStatus.fromOfferStatus(offer.status),
            obligatoryExtrasKeys =
                offer.offerExtras
                    .filter { it.obligatory == true }
                    .map { it.extrasKey() }
                    .toSet(),
            extras =
                filteredOfferExtras
                    .filter { it.first.shouldDisplay() }
                    .map { yachtExtrasMapper.toBasicDto(it.first, currency, it.second) }
                    .toSet(), // TODO remove this?
            locationFrom = offer.locationFrom?.toDto(),
            locationTo = offer.locationTo?.toDto(),
            checkin = offer.checkin,
            checkout = offer.checkout,
            numberOfDays = offer.numberOfDays().toShort(),
            clientPricePerDayEur = pricePerDayEur,
            clientPricePerDayInfo = exchangeRateCalculationService.calculatePriceInfo(pricePerDayEur, currency),
            listPriceEur = offer.extBasePrice,
            listPriceInfo = exchangeRateCalculationService.calculatePriceInfo(offer.extBasePrice, currency),
        )
    }
}
