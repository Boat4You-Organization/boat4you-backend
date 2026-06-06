package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.toCustomerStatus
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
        hasLiveOption: Boolean = false,
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
            // Honest customer status (Deploy 4): collapse the partner OfferStatus
            // onto the 4-state ExternalReservationStatus. A partner OPTION /
            // OPTION_WAITING that no longer has a live external_reservations hold
            // (optionExpiration lapsed) is bookable again -> FREE. RESERVED ->
            // RESERVATION, SERVICE stays SERVICE (both hard-blocked on the FE),
            // OPTION_EXPIRED / CANCELLED / INFO -> FREE.
            status =
                offer.status?.let { s ->
                    val collapsed = s.toCustomerStatus()
                    if (collapsed == ExternalReservationStatus.OPTION && !hasLiveOption) {
                        ExternalReservationStatus.FREE
                    } else {
                        collapsed
                    }
                } ?: ExternalReservationStatus.FREE,
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
