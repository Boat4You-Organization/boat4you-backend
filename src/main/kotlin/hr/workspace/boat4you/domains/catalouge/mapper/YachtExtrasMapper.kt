package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.InternalCalcDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtExtrasDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.toDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class YachtExtrasMapper(
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
) {
    fun toDto(
        extras: YachtExtra,
        currency: CurrencyEnum?,
    ): YachtExtrasDto {
        return YachtExtrasDto(
            id = extras.id!!,
            name = extras.name,
            payableInBase = extras.payableInBase!!,
            obligatory = extras.obligatory!!,
            priceEur = extras.price!!,
            priceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    extras.price!!,
                    currency,
                ),
            unit = extras.unit,
            extras = extras.extras?.toDto(),
            key = extras.extrasKey(),
            isStartingPrice = null,
        )
    }

    fun toBasicDto(
        extras: YachtExtra,
        currency: CurrencyEnum?,
    ): YachtExtrasDto {
        return YachtExtrasDto(
            id = extras.id!!,
            name = extras.name,
            payableInBase = extras.payableInBase!!,
            obligatory = extras.obligatory!!,
            priceEur = extras.price!!,
            priceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    extras.price!!,
                    currency,
                ),
            unit = extras.unit,
            extras = null,
            key = extras.extrasKey(),
            isStartingPrice = null,
        )
    }

    fun toBasicDto(
        extras: OfferExtra,
        currency: CurrencyEnum?,
        isStartingPrice: Boolean?,
    ): YachtExtrasDto {
        return YachtExtrasDto(
            id = extras.id!!,
            name = extras.name,
            payableInBase = extras.payableInBase!!,
            obligatory = extras.obligatory!!,
            priceEur = extras.price!!,
            priceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    extras.price!!,
                    currency,
                ),
            unit = extras.unit,
            extras = null,
            key = extras.extrasKey(),
            isStartingPrice = isStartingPrice,
        )
    }

    fun toExtrasPriceDto(
        reservationExtras: ReservationExtra,
        currency: CurrencyEnum?,
    ): ExtrasPriceDto {
        return ExtrasPriceDto(
            id = reservationExtras.id!!,
            name = reservationExtras.name!!,
            labelCode = reservationExtras.extras?.labelCode,
            priceEur = reservationExtras.price ?: BigDecimal.ZERO,
            priceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    reservationExtras.price,
                    currency,
                ),
            obligatory = reservationExtras.obligatory!!,
            payableInBase = reservationExtras.payableAtBase!!,
            unit = reservationExtras.unit!!,
            unitPriceEur = reservationExtras.unitPrice ?: BigDecimal.ZERO,
            unitPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    reservationExtras.unitPrice,
                    currency,
                ),
            key = reservationExtras.yachtExtrasKey!!,
            externalId = reservationExtras.externalId,
            extrasId = reservationExtras.extras?.id,
            isStartingPrice = null,
        )
    }

    fun toExtrasPriceDto(
        calcDto: InternalCalcDto,
        currency: CurrencyEnum?,
    ): ExtrasPriceDto {
        return ExtrasPriceDto(
            id = calcDto.id,
            name = calcDto.name,
            labelCode = calcDto.labelCode,
            priceEur = calcDto.getFinalPrice(),
            priceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    calcDto.getFinalPrice(),
                    currency,
                ),
            obligatory = calcDto.obligatory,
            payableInBase = calcDto.payableInBase,
            unit = calcDto.getFinalUnit(),
            unitPriceEur = calcDto.unitPriceEur,
            unitPriceInfo =
                exchangeRateCalculationService.calculatePriceInfo(
                    calcDto.unitPriceEur,
                    currency,
                ),
            key = calcDto.extrasKey(),
            extrasId = calcDto.extrasId,
            externalId = calcDto.externalId,
            isStartingPrice = calcDto.isStartingPrice,
        )
    }

    fun toInternalCalc(yachtExtras: YachtExtra): InternalCalcDto {
        return InternalCalcDto(
            id = yachtExtras.id!!,
            name = yachtExtras.name!!,
            labelCode = yachtExtras.extras?.labelCode,
            unitPriceEur = yachtExtras.price ?: BigDecimal.ZERO,
            unit = yachtExtras.unit!!,
            obligatory = yachtExtras.obligatory ?: false,
            payableInBase = yachtExtras.payableInBase ?: false,
            offerPriceEur = null,
            offerUnit = null,
            calcPriceEur = null,
            extrasId = yachtExtras.extras?.id,
            externalId = yachtExtras.externalId,
            isStartingPrice = null,
        )
    }

    fun toInternalCalc(
        offerExtras: OfferExtra,
        isStartingPrice: Boolean?,
    ): InternalCalcDto {
        return InternalCalcDto(
            id = offerExtras.id!!,
            name = offerExtras.name!!,
            labelCode = offerExtras.extras?.labelCode,
            unitPriceEur = offerExtras.price ?: BigDecimal.ZERO,
            unit = offerExtras.unit!!,
            obligatory = offerExtras.obligatory ?: false,
            payableInBase = offerExtras.payableInBase ?: false,
            offerPriceEur = offerExtras.price!!,
            offerUnit = offerExtras.unit!!,
            calcPriceEur = null,
            extrasId = offerExtras.extras?.id,
            externalId = offerExtras.externalId,
            isStartingPrice = isStartingPrice,
        )
    }
}
