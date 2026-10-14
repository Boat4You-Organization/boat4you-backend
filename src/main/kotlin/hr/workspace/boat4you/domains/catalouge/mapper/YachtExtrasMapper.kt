package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.InternalCalcDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtExtrasDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.partner.PartnerCopySanitizer
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.toDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * R01 (owner rule 14.10.2026): `partnerCopy` = the owning yacht is EXTERNAL. Then the DTO `name` and `description` go
 * through [PartnerCopySanitizer]; `key`, `paymentType` (classify) and [toInternalCalc] always use the RAW stored name,
 * which is a join key (DESIGN WS-R01 D2). A CUSTOM yacht's extras are our own copy and pass unchanged.
 */
@Component
class YachtExtrasMapper(
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
) {
    /**
     * The partner row id is served to SYSTEM_ADMIN only: MMK ids end in the operator's company id, and the
     * operator is never shown publicly. Same null-safe authority check as YachtMapper.isAdminUser().
     */
    private fun partnerIdForAdmin(externalId: Long?): Long? {
        val authentication = SecurityContextHolder.getContext().authentication
        val isAdmin = authentication?.authorities?.any { it.authority == "SYSTEM_ADMIN" } ?: false
        return if (isAdmin) externalId else null
    }

    fun toDto(
        extras: YachtExtra,
        currency: CurrencyEnum?,
        partnerCopy: Boolean,
    ): YachtExtrasDto {
        return YachtExtrasDto(
            id = extras.id!!,
            name = PartnerCopySanitizer.extraName(extras.name, partnerCopy),
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
            description = PartnerCopySanitizer.description(extras.description, partnerCopy),
            paymentType = extras.paymentType,
            externalId = partnerIdForAdmin(extras.externalId),
        )
    }

    fun toBasicDto(
        extras: YachtExtra,
        currency: CurrencyEnum?,
        partnerCopy: Boolean,
    ): YachtExtrasDto {
        return YachtExtrasDto(
            id = extras.id!!,
            name = PartnerCopySanitizer.extraName(extras.name, partnerCopy),
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
            description = PartnerCopySanitizer.description(extras.description, partnerCopy),
            paymentType = extras.paymentType,
            externalId = partnerIdForAdmin(extras.externalId),
        )
    }

    fun toBasicDto(
        extras: OfferExtra,
        currency: CurrencyEnum?,
        isStartingPrice: Boolean?,
        partnerCopy: Boolean,
    ): YachtExtrasDto {
        return YachtExtrasDto(
            id = extras.id!!,
            name = PartnerCopySanitizer.extraName(extras.name, partnerCopy),
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
            description = PartnerCopySanitizer.description(extras.description, partnerCopy),
            paymentType = extras.paymentType,
            externalId = partnerIdForAdmin(extras.externalId),
        )
    }

    /** A booked extra (my-bookings, admin): the snapshot name is shown cleaned, classify keeps the raw one. */
    fun toExtrasPriceDto(
        reservationExtras: ReservationExtra,
        currency: CurrencyEnum?,
        partnerCopy: Boolean,
    ): ExtrasPriceDto {
        return ExtrasPriceDto(
            id = reservationExtras.id!!,
            name = PartnerCopySanitizer.extraName(reservationExtras.name!!, partnerCopy)!!,
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
            // Since V9_82 the booking stores the payment type it was made with
            // (D6, 14.10.2026), so a later classifier change never rewrites a
            // booked extra. Older rows have none: they keep the historical
            // on-the-fly classification by name + flag, unchanged.
            paymentType = reservationExtras.paymentType ?: hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.classify(
                name = reservationExtras.name,
                price = reservationExtras.price,
                payableInBase = reservationExtras.payableAtBase ?: false,
            ),
        )
    }

    /**
     * The price calculation's own rows keep the RAW name: PriceCalculationService dedupes the NauSys re-quote on it and
     * ReservationFlowMutationService stores it as the booking snapshot. The public price response is cleaned once at the
     * end by [forDisplay].
     *
     * @param optionalOnSite The D6 switch `EXTRAS_ZERO_OPTIONAL_ON_SITE`, read once per price calculation; it only
     *                       matters for a row without a persisted payment type (see `ExtraPaymentType.zeroPrice`).
     */
    fun toExtrasPriceDto(
        calcDto: InternalCalcDto,
        currency: CurrencyEnum?,
        optionalOnSite: Boolean,
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
            // 23.4.2026: prefer the persisted paymentType from the entity
            // (Nausys sync now writes it directly from calculationType;
            // MMK rows still run through classify). Falling back to the
            // classify() regex keeps behavior intact for pre-backfill rows.
            paymentType = calcDto.paymentType
                ?: hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.classify(
                    name = calcDto.name,
                    price = calcDto.getFinalPrice(),
                    payableInBase = calcDto.payableInBase,
                    obligatory = calcDto.obligatory,
                    optionalOnSite = optionalOnSite,
                ),
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
            paymentType = yachtExtras.paymentType,
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
            paymentType = offerExtras.paymentType,
        )
    }

    companion object {
        /** R01: the public price-calculation response with display names (EXTERNAL yachts only); keys and types unchanged. */
        fun forDisplay(
            price: PriceCalcDto,
            partnerCopy: Boolean,
        ): PriceCalcDto {
            if (!partnerCopy) return price

            fun ExtrasPriceDto.clean() = copy(name = PartnerCopySanitizer.extraName(name) ?: name)
            return price.copy(
                selectedExtrasAtBase = price.selectedExtrasAtBase.map { it.clean() },
                selectedExtrasInPrice = price.selectedExtrasInPrice.map { it.clean() },
            )
        }
    }
}
