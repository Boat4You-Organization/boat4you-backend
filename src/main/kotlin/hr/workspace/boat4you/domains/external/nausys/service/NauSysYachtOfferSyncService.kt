package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferPaymentPlan
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.utils.Matchers
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.collections.all
import kotlin.collections.maxOfOrNull
import kotlin.collections.minOfOrNull

@Service
class NauSysYachtOfferSyncService(
    private val externalMappingService: ExternalMappingService,
    private val externalSystemService: ExternalSystemService,
    private val offerRepository: OfferRepository,
    private val locationQueryingService: LocationQueryingService,
    private val extraRepository: ExtraRepository,
    private val offerExtraRepository: OfferExtraRepository,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val yachtRepository: YachtRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        /**
         * Marker for offers that were returned by NauSys for *other* weeks of the same yacht but not for
         * the exact (dateFrom, dateTo) we just synced. Treated as pre-reserved so the yacht still surfaces
         * in search with a "Pre-reserved" badge instead of vanishing.
         *
         * We deliberately do NOT apply this marker when a yacht is completely absent from the response —
         * that signal is too weak (credential-scoped filtering, API hiccups, yacht removed) and produced
         * widespread false positives on cross-agency yachts. The MMK-style pattern of "if we didn't see
         * it, it's gone" is only safe when the API returns everything, which NauSys does not under our
         * single-credential setup. See `CLAUDE.md` > "NauSys under-option handling".
         *
         * On recovery (yacht comes back in a later response for this exact week), updateOffer() overwrites
         * status back to FREE and ext_status to the raw NauSys value — no additional cleanup needed.
         */
        const val SYNTHETIC_DISAPPEARANCE_EXT_STATUS = "SYNTHETIC_DISAPPEARANCE"
    }

    @Transactional
    fun syncOffers(
        agency: Agency,
        nausysOffers: RestFreeYachtList,
        allAgencyYachts: List<Yacht>,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getCachedAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
            )
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        val syncedOffers = mutableSetOf<Long>()
        var skippedCount = 0
        var skippedYachtCount = 0

        nausysOffers.freeYachts?.groupBy { it.yachtId }?.forEach { (yachtId, nausysYachtOffers) ->
            // Defensive: same NPE risk as MmkYachtOfferSyncService — partner may
            // send yachtId we haven't mapped yet. Skip + warn instead of `!!` chain.
            val mapping = allMappings.find { it.externalId == yachtId }
            val yacht = mapping?.systemId?.let { sysId -> allAgencyYachts.find { it.id == sysId } }
            if (yacht == null) {
                log.warn(
                    "Skipping ${nausysYachtOffers.size} NauSys offers for agency=${agency.id} yachtId=$yachtId " +
                        "— no Yacht mapping or yacht not loaded (mapping.systemId=${mapping?.systemId})",
                )
                skippedYachtCount++
                return@forEach
            }

            val existingYachtOffers = getOffersForYacht(yacht, nausysYachtOffers)

            if (existingYachtOffers.isEmpty()) {
                nausysYachtOffers.forEach { nausysOffer ->
                    if (!updateOffer(Offer(), yacht, nausysOffer, allLocationMappings)) skippedCount++
                }
            } else {
                nausysYachtOffers.forEach { nausysOffer ->
                    val existingOffer =
                        existingYachtOffers.find { it.dateFrom == nausysOffer.periodFrom!!.value && it.dateTo == nausysOffer.periodTo!!.value }
                    // Mark existing offer alive BEFORE updateOffer so a missing-location skip
                    // doesn't flip it to SYNTHETIC_DISAPPEARANCE below.
                    if (existingOffer != null) {
                        syncedOffers.add(existingOffer.id!!)
                    }
                    val updated =
                        if (existingOffer == null) {
                            updateOffer(Offer(), yacht, nausysOffer, allLocationMappings)
                        } else {
                            updateOffer(existingOffer, yacht, nausysOffer, allLocationMappings)
                        }
                    if (!updated) skippedCount++
                }
            }

            // Handle offers that existed for this exact (dateFrom, dateTo) but were not refreshed
            // by the current response (yacht was in response but didn't return an offer for this week).
            //
            // SYNTHETIC_OPTION rows (created by NauSysAvailabilitySyncService) are skipped — they'd
            // re-synthesize on the next availability sync, causing thrashing and brief invisibility.
            // SYNTHETIC_DISAPPEARANCE rows are likewise skipped so repeated misses don't flip them.
            //
            // For FREE offers that disappeared, we mark them as OPTION_WAITING with
            // SYNTHETIC_DISAPPEARANCE marker so they surface in search as pre-reserved instead of
            // silently vanishing (UNAVAILABLE is filtered out by yacht_search_view).
            existingYachtOffers
                .filter { it.dateFrom == dateFrom && it.dateTo == dateTo }
                .filter {
                    !syncedOffers.contains(it.id!!) &&
                        it.status != OfferStatus.UNAVAILABLE &&
                        it.extStatus != NauSysAvailabilitySyncService.SYNTHETIC_OPTION_EXT_STATUS &&
                        it.extStatus != SYNTHETIC_DISAPPEARANCE_EXT_STATUS
                }
                .forEach { offer ->
                    if (offer.status == OfferStatus.FREE) {
                        offer.status = OfferStatus.OPTION_WAITING
                        offer.extStatus = SYNTHETIC_DISAPPEARANCE_EXT_STATUS
                        log.info(
                            "Marked offer ${offer.id} (yacht ${offer.yacht?.id}) as SYNTHETIC_DISAPPEARANCE " +
                                "for $dateFrom-$dateTo (in response, but no offer for this week)",
                        )
                    } else {
                        // Preserve legacy UNAVAILABLE transition for non-FREE statuses
                        offer.status = OfferStatus.UNAVAILABLE
                    }
                    offerRepository.save(offer)
                }
        }

        if (skippedCount > 0) {
            log.warn("NauSys offer sync for agency ${agency.id}: skipped $skippedCount offers due to missing Location mappings")
        }
        if (skippedYachtCount > 0) {
            log.warn("NauSys offer sync for agency ${agency.id}: skipped $skippedYachtCount yachts due to missing Yacht mappings")
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun syncOffersForAsync(nausysAllOffers: List<RestFreeYacht>) {
        val yachtExternalIds = nausysAllOffers.map { it.yachtId!! }.toList()
        if (yachtExternalIds.isEmpty()) {
            log.warn("No yacht external IDs found in Nausys offers, skipping sync.")
            return
        }

        val allYachts =
            yachtRepository.findByExternalIdsAndExternalSystemId(
                yachtExternalIds,
                ExternalSystemEnum.NAUSYS.value.toLong(),
            )
        val allYachtMappings =
            externalMappingService.findAllByTypeAndExternalSystemAndExternalIdIn(
                Yacht::class.simpleName.toString(),
                ExternalSystemEnum.NAUSYS.value,
                yachtExternalIds,
            )
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)

        var skippedCount = 0
        nausysAllOffers.groupBy { it.yachtId }?.forEach { (nausysYachtId, nausysOffers) ->
            val mapping = allYachtMappings.find { it.externalId == nausysYachtId!! } ?: return@forEach
            val yacht = allYachts.find { y -> y.id == mapping.systemId } ?: return@forEach

            val existingYachtOffers = getOffersForYacht(yacht, nausysOffers)

            nausysOffers.forEach { nausysOffer ->
                val existingOffer =
                    existingYachtOffers.find {
                        it.dateFrom == nausysOffer.periodFrom!!.value!! &&
                            it.dateTo == nausysOffer.periodTo!!.value!!
                    }
                val updated =
                    if (existingOffer == null) {
                        updateOffer(Offer(), yacht, nausysOffer, allLocationMappings)
                    } else {
                        updateOffer(existingOffer, yacht, nausysOffer, allLocationMappings)
                    }
                if (!updated) skippedCount++
            }
        }

        if (skippedCount > 0) {
            log.warn("NauSys async offer sync: skipped $skippedCount offers due to missing Location mappings")
        }
    }

    private fun getOffersForYacht(
        yacht: Yacht,
        nausysYachtOffers: List<RestFreeYacht>,
    ): List<Offer> {
        val minDateFrom = nausysYachtOffers.minOfOrNull { it.periodFrom!!.value!! }
        val maxDateTo = nausysYachtOffers.maxOfOrNull { it.periodTo!!.value!! }
        val allDatesEqual =
            nausysYachtOffers.all { it.periodFrom!!.value!! == minDateFrom && it.periodTo!!.value!! == maxDateTo }

        // use for better db index usage
        return if (allDatesEqual) {
            offerRepository.findAllByYachtAndDateFromAndDateTo(yacht, minDateFrom!!, maxDateTo!!)
        } else {
            offerRepository.findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
                yacht,
                minDateFrom!!,
                maxDateTo!!,
            )
        }
    }

    /**
     * @return true if the offer was updated and saved; false if it was skipped because a required
     * Location mapping or Location row was missing. A skipped offer must not be deactivated /
     * flipped to SYNTHETIC_DISAPPEARANCE by the caller — the row in DB (if any) keeps its state.
     */
    private fun updateOffer(
        offer: Offer,
        yacht: Yacht,
        nausysOffer: RestFreeYacht,
        allLocationMappings: List<ExternalMapping>,
    ): Boolean {
        val locationFromMapping =
            allLocationMappings.find { location -> location.externalId == nausysOffer.locationFromId!!.toLong() }
        val locationFrom =
            locationFromMapping?.systemId?.let { locationQueryingService.getCachedLocationById(it) }
        val locationToMapping =
            allLocationMappings.find { location -> location.externalId == nausysOffer.locationToId!!.toLong() }
        val locationTo =
            locationToMapping?.systemId?.let { locationQueryingService.getCachedLocationById(it) }

        // Without both Locations the offer fails @NotNull pre-update validation on the next
        // session auto-flush, which aborts the entire transaction. Skip silently with a warn
        // so one broken upstream mapping doesn't wipe an agency's batch.
        if (locationFrom == null || locationTo == null) {
            log.warn(
                "Skipping NauSys offer for yacht=${yacht.id}: missing Location " +
                    "(locationFromId=${nausysOffer.locationFromId} → mapping=${locationFromMapping?.systemId}, " +
                    "locationToId=${nausysOffer.locationToId} → mapping=${locationToMapping?.systemId})",
            )
            return false
        }

        offer.yacht = yacht
        offer.status = OfferStatus.fromNausysValue(nausysOffer.status)
        offer.extStatus = nausysOffer.status
        offer.product = CharterType.UNKNOWN
        offer.locationFrom = locationFrom
        offer.locationTo = locationTo
        offer.dateFrom = nausysOffer.periodFrom!!.value
        offer.dateTo = nausysOffer.periodTo!!.value
        offer.checkin = nausysOffer.checkIn ?: yacht.defaultCheckin ?: "Contact for details"
        offer.checkout = nausysOffer.checkOut ?: yacht.defaultCheckout ?: "Contact for details"
        offer.type = OfferType.getFromDates(offer.dateFrom!!, offer.dateTo!!)

        offer.deposit = nausysOffer.price!!.depositAmount?.toBigDecimal()
        offer.depositInsured =
            nausysOffer.price!!.depositWhenInsuredAmount?.toBigDecimal()

        val discounts = nausysOffer.price!!.discounts?.map { it.amount?.toBigDecimal() ?: BigDecimal.ZERO }
        offer.extTotalDiscount = if (!discounts.isNullOrEmpty()) discounts.sumOf { it } else null
        offer.extDiscountPerc = null
        offer.totalDiscount = null

        offer.extBasePrice = nausysOffer.price!!.priceListPrice?.toBigDecimal()
        offer.extClientPrice = nausysOffer.price!!.clientPrice?.toBigDecimal()
        offer.extTotalPrice = nausysOffer.totalPriceWithExtras?.toBigDecimal()

        val clientPrice =
            PriceCalculations.calculateClientPrice(
                nausysOffer.price!!.clientPrice!!.toBigDecimal(),
                yacht.agency!!.getDiscountOrZero(),
                yacht.excludeDiscount != true,
            )
        offer.clientPrice = clientPrice
        offer.agencyCommission = nausysOffer.price!!.clientPrice!!.toBigDecimal().minus(clientPrice)
        // Broker commission — partner's exact per-offer figure. Nausys
        // exposes it as `price.agencyCommission` (a string in their DTO).
        // Null when the partner didn't include it on this offer (rare).
        offer.brokerCommission = nausysOffer.price!!.agencyCommission?.toBigDecimal()

        val obligatoryExtrasPrice =
            nausysOffer.obligatoryExtras
                ?.filter { it.calculationType?.value != "SEPARATE_PAYMENT" }
                ?.sumOf { it.totalPrice?.toBigDecimal() ?: BigDecimal.ZERO }
                ?: BigDecimal.ZERO
        offer.totalPrice = clientPrice + obligatoryExtrasPrice
        offer.obligatoryExtrasPrice = obligatoryExtrasPrice

        offerRepository.save(offer)

        handleExtras(offer, nausysOffer)

        // Partner payment plan = source of truth for installment timing.
        // Re-enabled 1.5.2026 — see MmkYachtOfferSyncService for the full note.
        if (!nausysOffer.paymentPlans.isNullOrEmpty()) {
            handlePaymentPlans(offer, nausysOffer)
        } else {
            offer.offerPaymentPlans.clear()
            offerRepository.save(offer)
        }
        return true
    }

    /**
     * There is a lot of duplicate code here, but it has different type so its easier to keep it like this
     */
    private fun handleExtras(
        offer: Offer,
        nausysOffer: RestFreeYacht,
    ) {
        val allExtras = extraRepository.findAll()
        val allExternalEquipment =
            externalEquipmentRepository.getCachedByExternalSystemId(ExternalSystemEnum.NAUSYS.value)
        val matchedIds = mutableSetOf<Long>()

        nausysOffer.obligatoryExtras?.forEach { nausysExtra ->
            // for each nausys equipment, find the external nausys equipment and try to match it with our equipment
            val externalEquipmentMatch =
                allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysExtra.serviceId!! && eq.type == ExternalEquipmentType.SERVICE }
            if (externalEquipmentMatch == null) {
                log.error("Nausys equipment not found for Nausys extras: $nausysExtra")
                return@forEach
            }
            val boat4youEquipmentMatch =
                allExtras.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                }
            // Per-offer condition note from NauSys (e.g. "must be paid 14 days
            // before embarkation"). Mirror MMK obligatory mapper which copies
            // `mmkExtra.description` — we do the same so the "small print"
            // surfaces under the extras name on /boat detail and admin offer.
            val nausysCondition = nausysExtra.condition?.let {
                it.textEN ?: it.textHR ?: it.textIT ?: it.textDE
            }?.takeIf { it.isNotBlank() }

            val equipmentAlreadyOnOffer =
                offer.offerExtras.find { ex ->
                    ex.externalId == nausysExtra.id
                }

            val obligPrice = nausysExtra.totalPrice?.toBigDecimal()
            val obligPayable = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
            val obligPaymentType = hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.fromNausysCalculationType(
                calculationType = nausysExtra.calculationType?.value,
                price = obligPrice,
            )
            if (equipmentAlreadyOnOffer != null) {
                equipmentAlreadyOnOffer.extras = boat4youEquipmentMatch
                equipmentAlreadyOnOffer.price = obligPrice
                equipmentAlreadyOnOffer.payableInBase = obligPayable
                equipmentAlreadyOnOffer.paymentType = obligPaymentType
                equipmentAlreadyOnOffer.externalUnit = nausysExtra.priceMeasureId.toString()
                equipmentAlreadyOnOffer.description = nausysCondition
                matchedIds.add(equipmentAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.extras = boat4youEquipmentMatch
            offerExtra.name = externalEquipmentMatch.name
            offerExtra.externalId = nausysExtra.id!!
            offerExtra.offer = offer
            offerExtra.price = obligPrice
            offerExtra.payableInBase = obligPayable
            offerExtra.paymentType = obligPaymentType
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = nausysExtra.priceMeasureId.toString()
            offerExtra.obligatory = true
            offerExtra.description = nausysCondition

            offerExtraRepository.save(offerExtra)
            offer.offerExtras.add(offerExtra)
            matchedIds.add(offerExtra.id!!)
        }

        nausysOffer.additionalExtras?.forEach { nausysExtra ->
            val externalEquipmentMatch =
                allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysExtra.extraId!! && eq.type?.name == nausysExtra.extrasType?.value }
            if (externalEquipmentMatch == null) {
                log.error("Nausys equipment not found for Nausys extras: $nausysExtra")
                return@forEach
            }
            val boat4youEquipmentMatch =
                allExtras.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                }
            val nausysCondition = nausysExtra.condition?.let {
                it.textEN ?: it.textHR ?: it.textIT ?: it.textDE
            }?.takeIf { it.isNotBlank() }

            val equipmentAlreadyOnOffer =
                offer.offerExtras.find { ex ->
                    ex.externalId == nausysExtra.id
                }

            val addPrice = nausysExtra.totalPrice?.toBigDecimal()
            val addPayable = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
            val addPaymentType = hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.fromNausysCalculationType(
                calculationType = nausysExtra.calculationType?.value,
                price = addPrice,
            )
            if (equipmentAlreadyOnOffer != null) {
                equipmentAlreadyOnOffer.extras = boat4youEquipmentMatch
                equipmentAlreadyOnOffer.price = addPrice
                equipmentAlreadyOnOffer.payableInBase = addPayable
                equipmentAlreadyOnOffer.paymentType = addPaymentType
                equipmentAlreadyOnOffer.externalUnit = nausysExtra.priceMeasureId.toString()
                equipmentAlreadyOnOffer.description = nausysCondition
                matchedIds.add(equipmentAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.extras = boat4youEquipmentMatch
            offerExtra.name = externalEquipmentMatch.name
            offerExtra.externalId = nausysExtra.id!!
            offerExtra.offer = offer
            offerExtra.price = addPrice
            offerExtra.payableInBase = addPayable
            offerExtra.paymentType = addPaymentType
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = nausysExtra.priceMeasureId.toString()
            offerExtra.obligatory = false
            offerExtra.description = nausysCondition

            offerExtraRepository.save(offerExtra)
            offer.offerExtras.add(offerExtra)
            matchedIds.add(offerExtra.id!!)
        }

        val toRemove = mutableListOf<OfferExtra>()
        offer.offerExtras.forEach { offerExtra ->
            if (matchedIds.none { it == offerExtra.id }) {
                toRemove.add(offerExtra)
            }
        }
        if (toRemove.isNotEmpty()) {
            offer.offerExtras.removeAll(toRemove)
            offerExtraRepository.deleteAll(toRemove)
        }
    }

    private fun handlePaymentPlans(
        offer: Offer,
        nausysOffer: RestFreeYacht,
    ) {
        // Create a set of dates from incoming payment plans for efficient lookup
        val incomingDates = nausysOffer.paymentPlans?.map { it.date?.value }?.toSet() ?: emptySet()

        // Remove payment plans that are not in the incoming list
        offer.offerPaymentPlans.removeIf { existingPlan ->
            existingPlan.date !in incomingDates
        }

        // Add or update payment plans
        nausysOffer.paymentPlans?.forEach { incomingPlan ->
            val planDate = incomingPlan.date?.value
            val existingPlan = offer.offerPaymentPlans.find { it.date == planDate }

            // Nausys percentage is whole-number percent (e.g. 20.00 means 20%),
            // not a decimal fraction. Divide by 100 before multiplying or the
            // stored amount ends up 100× the actual price (offer 319593 case:
            // 20% × 4,950 € rendered as 99,000 € until this fix).
            val amount =
                incomingPlan.percentage?.let {
                    it.divide(BigDecimal(100), 4, RoundingMode.HALF_UP).multiply(
                        offer.clientPrice ?: BigDecimal.ZERO,
                    )
                }

            if (existingPlan != null) {
                // Update existing plan
                existingPlan.amount = amount
                existingPlan.percentage = incomingPlan.percentage
            } else {
                // Add new plan
                val offerPaymentPlan = OfferPaymentPlan()
                offerPaymentPlan.offer = offer
                offerPaymentPlan.date = planDate
                offerPaymentPlan.amount = amount
                offerPaymentPlan.percentage = incomingPlan.percentage
                offer.offerPaymentPlans.add(offerPaymentPlan)
            }
        }

        offerRepository.save(offer)
    }
}
