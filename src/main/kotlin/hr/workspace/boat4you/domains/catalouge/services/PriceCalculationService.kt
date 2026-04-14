package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.collections.containsAll
import kotlin.collections.get

@Service
@Transactional(readOnly = true)
class PriceCalculationService(
    private val yachtExtraRepository: YachtExtraRepository,
    private val yachtExtrasMapper: YachtExtrasMapper,
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
    private val externalBaseRepository: ExternalBaseRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this::class.java.name)

    fun calculatePrice(
        yacht: Yacht,
        offer: Offer,
        currency: CurrencyEnum?,
        selectedExtras: Set<String>,
    ): PriceCalcDto {
        // MMK has only obligatory extras on offer, so we need to calculate price for others
        val externalBasesExternalIds =
            externalBaseRepository
                .findByAgencyIdAndLocationId(yacht.agency!!.id!!, yacht.location!!.id!!)
                .map { it.externalId!! }
        val yachtExtras = yachtExtraRepository.findAllByYacht(yacht)
        val offerExtras = offer.filterDuplicateExtras()

        // 1. calc take all yacht extras that are obligatory or selected extras
        val allYachtExtras =
            yachtExtras
                .filter {
                    (it.validFrom == null || it.validFrom!!.isBefore(offer.dateFrom)) &&
                        (it.validTo == null || it.validTo!!.isAfter(offer.dateTo))
                }.filter {
                    it.validForBases == null || externalBasesExternalIds.isEmpty() ||
                        it.validForBases!!.any { e ->
                            e in externalBasesExternalIds
                        }
                }.filter { it.obligatory == true || selectedExtras.contains(it.extrasKey()) }
                .map { yachtExtrasMapper.toInternalCalc(it) }

        val allOfferExtras =
            offerExtras
                .filter { it.first.obligatory == true || selectedExtras.contains(it.first.extrasKey()) }
                .map { yachtExtrasMapper.toInternalCalc(it.first, it.second) }

        // 2. get full list of extras by extrasId or name, where prices are taken from offer extras if they exist
        val allOfferExtrasMap =
            allOfferExtras
                .associateBy { it.extrasKey() }
                .toMutableMap()
        val allYachtExtrasMap =
            allYachtExtras
                .associateBy { it.extrasKey() }
                .toMutableMap()
        val totalExtrasMap =
            buildMap {
                putAll(allYachtExtrasMap)
                putAll(allOfferExtrasMap) // Later putAll call override earlier ones
            }

        // 3. split into at base and in price
        val flattenedExtras = totalExtrasMap.values
        val selectedExtrasAtBase =
            flattenedExtras
                .filter { it.payableInBase }
        selectedExtrasAtBase.forEach { extraInBase ->
            val result =
                PriceCalculations.calculateExtrasPrice(
                    extraInBase.getFinalPrice(),
                    extraInBase.getFinalUnit(),
                    offer.dateFrom!!,
                    offer.dateTo!!,
                    yacht.maxPersons?.toInt(),
                )
            if (result.isSuccess) {
                extraInBase.calcPriceEur = result.getOrNull()
            }
        }

        // 4. for in price check prices and calc based on unit type
        var potentiallyIncorrectCalc = false
        val selectedExtrasInPrice = flattenedExtras.filter { !it.payableInBase }
        selectedExtrasInPrice.forEach { extraInPrice ->
            val result =
                PriceCalculations.calculateExtrasPrice(
                    extraInPrice.getFinalPrice(),
                    extraInPrice.getFinalUnit(),
                    offer.dateFrom!!,
                    offer.dateTo!!,
                    yacht.maxPersons?.toInt(),
                )
            if (result.isSuccess) {
                extraInPrice.calcPriceEur = result.getOrNull()
            } else {
                potentiallyIncorrectCalc = true
            }
        }

        // detect if there are selected extras in price that have option with higher price
        var multipleExtrasInPriceOptionsAvailable = false
        selectedExtrasInPrice.forEach { extraInPrice ->
            if (extraInPrice.isStartingPrice == true) {
                multipleExtrasInPriceOptionsAvailable = true
            }
        }
        var multipleExtrasInBaseOptionsAvailable = false
        selectedExtrasAtBase.forEach { extraInPrice ->
            if (extraInPrice.isStartingPrice == true) {
                multipleExtrasInBaseOptionsAvailable = true
            }
        }

        val extrasPrice = selectedExtrasInPrice.sumOf { it.calcPriceEur ?: BigDecimal.ZERO }
        val totalPrice = offer.clientPrice!! + extrasPrice
        val pricePerDayEur = offer.pricePerDayEur()

        return PriceCalcDto(
            offerId = offer.id!!,
            yachtId = offer.yacht!!.id!!,
            dateFrom = offer.dateFrom!!,
            dateTo = offer.dateTo!!,
            clientPriceEur = offer.clientPrice!!,
            clientPriceInfo = exchangeRateCalculationService.calculatePriceInfo(offer.clientPrice, currency),
            totalPriceEur = totalPrice,
            totalPriceInfo = exchangeRateCalculationService.calculatePriceInfo(totalPrice, currency),
            totalDiscountEur = offer.totalDiscount ?: BigDecimal.ZERO,
            totalDiscountInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    offer.totalDiscount,
                    currency,
                ),
            selectedExtrasAtBase =
                selectedExtrasAtBase.map {
                    yachtExtrasMapper.toExtrasPriceDto(it, currency)
                },
            selectedExtrasInPrice =
                selectedExtrasInPrice.map {
                    yachtExtrasMapper.toExtrasPriceDto(it, currency)
                },
            inquire = potentiallyIncorrectCalc,
            securityDeposit = yacht.deposit,
            insuredSecurityDeposit = yacht.insuredDeposit,
            depositCurrency = yacht.depositCurrency,
            numberOfDays = offer.numberOfDays().toShort(),
            clientPricePerDayEur = pricePerDayEur,
            clientPricePerDayInfo = exchangeRateCalculationService.calculatePriceInfo(pricePerDayEur, currency),
            multipleExtrasInPriceOptionsAvailable = multipleExtrasInPriceOptionsAvailable,
            multipleExtrasInBaseOptionsAvailable = multipleExtrasInBaseOptionsAvailable,
            extrasPriceCalcInquire = potentiallyIncorrectCalc,
        )
    }
}
