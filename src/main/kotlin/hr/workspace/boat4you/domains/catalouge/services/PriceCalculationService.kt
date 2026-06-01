package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.external.nausys.service.NauSysObligatoryExtrasService
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
    private val nauSysObligatoryExtrasService: NauSysObligatoryExtrasService,
) {
    private val log: Logger = LoggerFactory.getLogger(this::class.java.name)

    fun calculatePrice(
        yacht: Yacht,
        offer: Offer,
        currency: CurrencyEnum?,
        selectedExtras: Set<String>,
    ): PriceCalcDto {
        // MMK has only obligatory extras on offer, so we need to calculate price for others.
        // Null-guard: some legacy yachts lack a location (e.g. DESSUS 3116, ADRIATIC PEARL
        // 5959) — without a location we can't resolve the agency-base mapping, so fall
        // back to an empty list which disables the base-scoped extras filter downstream.
        val agencyId = yacht.agency?.id
        val locationId = yacht.location?.id
        val externalBasesExternalIds =
            if (agencyId != null && locationId != null) {
                externalBaseRepository
                    .findByAgencyIdAndLocationId(agencyId, locationId)
                    .map { it.externalId!! }
            } else {
                emptyList()
            }
        val yachtExtras = yachtExtraRepository.findAllByYacht(yacht)
        val offerExtras = offer.filterDuplicateExtras()

        // 1. calc take all yacht extras that are obligatory or selected extras
        val allYachtExtras =
            yachtExtras
                .filter {
                    // Keep the variant whose validity window COVERS the charter
                    // check-in date (the period being booked). Replaces strict
                    // whole-booking containment, which dropped the period row on
                    // bookings straddling a season boundary and — combined with a
                    // shared extras label — let the wrong-season / surcharge price
                    // be charged. Charters are priced off their start date.
                    (it.validFrom == null || !it.validFrom!!.isAfter(offer.dateFrom)) &&
                        (it.validTo == null || !it.validTo!!.isBefore(offer.dateFrom))
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
        // Dedupe by the stable partner extra id (externalId) when present, NOT by
        // extrasKey (name/label). The same NauSys extra can carry different names in
        // yacht_extras vs offer_extras when the partner renames it between syncs —
        // e.g. Lagoon 39 "Gin Tonic" had one Comfort Pack (external_id 5010210436201502)
        // surfacing as both "Comfort Pack 39/40/42" and "...38/39/40/42", so it was
        // billed TWICE at the marina. Same externalId -> one entry (offer overrides
        // yacht below). Extras without an externalId fall back to extrasKey.
        val allOfferExtrasMap =
            allOfferExtras
                .associateBy { it.externalId?.toString() ?: it.extrasKey() }
                .toMutableMap()
        val allYachtExtrasMap =
            allYachtExtras
                .associateBy { it.externalId?.toString() ?: it.extrasKey() }
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

        val result = PriceCalcDto(
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

        return mergeNausysObligatory(result, yacht, offer, currency, selectedExtras)
    }

    /**
     * Some partners (NauSys/Navigare) promote an extra to obligatory only once
     * another is selected — e.g. Damage Waiver becomes mandatory when a Skipper is
     * added. That rule lives at the partner, not in our catalogue, so we replay the
     * exact selection against NauSys (getFreeYachts + serviceIDs) and fold any newly
     * obligatory service into the price — both the customer preview and the stored
     * reservation total/extras, so the charged amount matches the partner. The
     * partner call is best-effort: on any miss we return the local price unchanged.
     */
    private fun mergeNausysObligatory(
        base: PriceCalcDto,
        yacht: Yacht,
        offer: Offer,
        currency: CurrencyEnum?,
        selectedExtras: Set<String>,
    ): PriceCalcDto {
        if (yacht.entryType != EntryType.EXTERNAL || selectedExtras.isEmpty()) return base
        val dateFrom = offer.dateFrom ?: return base
        val dateTo = offer.dateTo ?: return base

        val obligatory =
            nauSysObligatoryExtrasService.obligatoryWithSelectedServices(yacht, dateFrom, dateTo, selectedExtras)
        if (obligatory.isEmpty()) return base

        // Keys already in the price (base obligatory like handling/preparation, plus
        // whatever the client explicitly selected) must not be double-counted.
        val alreadyCounted =
            (base.selectedExtrasInPrice + base.selectedExtrasAtBase).map { it.key }.toMutableSet()
        alreadyCounted += selectedExtras

        val yachtExtrasByName = yachtExtraRepository.findAllByYacht(yacht).associateBy { it.name }

        val promoted =
            obligatory.mapNotNull { o ->
                val ye = yachtExtrasByName[o.name]
                val key = ye?.extrasKey() ?: o.name
                // add() == false -> already accounted for (base obligatory / selected) -> skip
                if (key.isBlank() || !alreadyCounted.add(key)) return@mapNotNull null
                ExtrasPriceDto(
                    id = ye?.id ?: 0,
                    name = o.name,
                    labelCode = ye?.extras?.labelCode,
                    priceEur = o.totalPrice,
                    priceInfo = exchangeRateCalculationService.calculatePriceInfo(o.totalPrice, currency),
                    obligatory = true,
                    payableInBase = false,
                    unit = ExtrasUnitType.PER_BOOKING,
                    unitPriceEur = o.totalPrice,
                    unitPriceInfo = exchangeRateCalculationService.calculatePriceInfo(o.totalPrice, currency),
                    key = key,
                    extrasId = ye?.extras?.id,
                    externalId = ye?.externalId,
                    isStartingPrice = null,
                    paymentType = ExtraPaymentType.fromNausysCalculationType(o.calculationType, o.totalPrice),
                )
            }
        if (promoted.isEmpty()) return base

        val newTotal = promoted.fold(base.totalPriceEur) { acc, e -> acc + e.priceEur }
        return base.copy(
            selectedExtrasInPrice = base.selectedExtrasInPrice + promoted,
            totalPriceEur = newTotal,
            totalPriceInfo = exchangeRateCalculationService.calculatePriceInfo(newTotal, currency),
        )
    }
}
