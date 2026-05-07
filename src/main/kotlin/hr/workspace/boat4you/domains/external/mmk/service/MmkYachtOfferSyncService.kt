package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
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
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.utils.Matchers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class MmkYachtOfferSyncService(
    private val externalSystemService: ExternalSystemService,
    private val externalMappingService: ExternalMappingService,
    private val offerRepository: OfferRepository,
    private val locationQueryingService: LocationQueryingService,
    private val yachtRepository: YachtRepository,
    private val extraRepository: ExtraRepository,
    private val offerExtraRepository: OfferExtraRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun syncOffersForAgency(
        agencyId: Long,
        mmkOffers: List<org.openapitools.client.mmk.model.Offer>,
    ) {
        val allAgencyYachts = yachtRepository.findAllByAgencyId(agencyId)
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getCachedAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agencyId,
            )
        val syncedOffers = mutableSetOf<Long>()
        var skippedCount = 0
        var skippedYachtCount = 0

        mmkOffers.groupBy { it.yachtId }.forEach { (yachtId, mmkOffers) ->
            // Defensive: MMK can send a yachtId we haven't mapped yet (e.g. partner
            // added a brand-new yacht between two yacht-sync runs, or yacht was removed
            // from agency before our External Mapping was reconciled). The old `mapping!!.systemId!!.find()!!`
            // chain NPE'd here and aborted the whole agency batch — we'd lose all
            // offers for the rest of this transaction.
            val mapping = allMappings.find { it.externalId == yachtId?.toLong() }
            val yacht = mapping?.systemId?.let { sysId -> allAgencyYachts.find { it.id == sysId } }
            if (yacht == null) {
                log.warn(
                    "Skipping ${mmkOffers.size} MMK offers for agency=$agencyId yachtId=$yachtId " +
                        "— no Yacht mapping or yacht not loaded (mapping.systemId=${mapping?.systemId})",
                )
                skippedYachtCount++
                return@forEach
            }

            val minDateFrom = mmkOffers.minOfOrNull { it.dateFrom.value!!.toLocalDate() }
            val maxDateTo = mmkOffers.maxOfOrNull { it.dateTo.value!!.toLocalDate() }

            val existingYachtOffers =
                offerRepository.findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
                    yacht,
                    minDateFrom!!,
                    maxDateTo!!,
                )

            mmkOffers.forEach { mmkOffer ->
                val existingOffer =
                    existingYachtOffers.find {
                        it.dateFrom == mmkOffer.dateFrom.value?.toLocalDate() &&
                            it.dateTo == mmkOffer.dateTo.value?.toLocalDate() &&
                            it.product == CharterType.fromMmkValue(mmkOffer.product)
                    }
                // Mark existing offer as "still alive" BEFORE updateOffer so a missing-location skip
                // (early return inside updateOffer) doesn't deactivate a perfectly valid DB row in
                // the deactivate-loop below. See V1_51-era log: a single broken Location mapping
                // would otherwise wipe an entire agency's offers.
                if (existingOffer != null) {
                    syncedOffers.add(existingOffer.id!!)
                }
                val updated =
                    if (existingOffer == null) {
                        updateOffer(Offer(), yacht, mmkOffer)
                    } else {
                        updateOffer(existingOffer, yacht, mmkOffer)
                    }
                if (!updated) skippedCount++
            }

            // deactivate all offers that are not returned by MMK
            // we call this method only once per yacht so no need to check by dates as in Nausys
            existingYachtOffers
                .filter { !syncedOffers.contains(it.id!!) && it.status != OfferStatus.UNAVAILABLE }
                .forEach { offer ->
                    offer.status = OfferStatus.UNAVAILABLE
                    offerRepository.save(offer)
                }
        }

        if (skippedCount > 0) {
            log.warn("MMK offer sync for agency $agencyId: skipped $skippedCount offers due to missing Location mappings")
        }
        if (skippedYachtCount > 0) {
            log.warn("MMK offer sync for agency $agencyId: skipped $skippedYachtCount yachts due to missing Yacht mappings")
        }
    }

    /**
     * @return true if the offer was updated and saved; false if it was skipped because a required
     * Location mapping or Location row was missing. A skipped offer must not be deactivated by the
     * caller — the row in DB (if any) keeps its current state.
     */
    private fun updateOffer(
        offer: Offer,
        yacht: Yacht,
        mmkOffer: org.openapitools.client.mmk.model.Offer,
    ): Boolean {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        val locationFromMapping =
            allLocationMappings.find { location -> location.externalId == mmkOffer.startBaseId }
        val locationFrom =
            locationFromMapping?.systemId?.let { locationQueryingService.getCachedLocationById(it) }
        val locationToMapping =
            allLocationMappings.find { location -> location.externalId == mmkOffer.endBaseId }
        val locationTo =
            locationToMapping?.systemId?.let { locationQueryingService.getCachedLocationById(it) }

        // Without both Locations the offer fails @NotNull pre-update validation on the next
        // session auto-flush, which aborts the entire transaction and wipes the agency's batch.
        // Skip silently (with a warn) so one broken upstream mapping doesn't mask a healthy sync.
        if (locationFrom == null || locationTo == null) {
            log.warn(
                "Skipping MMK offer for yacht=${yacht.id}: missing Location " +
                    "(startBaseId=${mmkOffer.startBaseId} → mapping=${locationFromMapping?.systemId}, " +
                    "endBaseId=${mmkOffer.endBaseId} → mapping=${locationToMapping?.systemId})",
            )
            return false
        }

        offer.yacht = yacht
        offer.status = OfferStatus.fromMmkValue(mmkOffer.status)
        offer.extStatus = mmkOffer.status.toString()

        offer.product = CharterType.fromMmkValue(mmkOffer.product)
        offer.locationFrom = locationFrom
        offer.locationTo = locationTo
        offer.dateFrom = mmkOffer.dateFrom.value!!.toLocalDate()
        offer.dateTo = mmkOffer.dateTo.value!!.toLocalDate()
        offer.checkin = mmkOffer.dateFrom.value!!.toLocalTime().toString()
        offer.checkout = mmkOffer.dateTo.value!!.toLocalTime().toString()
        offer.type = OfferType.getFromDates(offer.dateFrom!!, offer.dateTo!!)

        offer.deposit = mmkOffer.securityDeposit?.toBigDecimal()
        offer.depositInsured = null

        offer.extTotalDiscount = null
        offer.extDiscountPerc = mmkOffer.discountPercentage.toBigDecimal()
        offer.totalDiscount = null

        offer.extBasePrice = mmkOffer.startPrice.toBigDecimal()
        offer.extClientPrice = mmkOffer.startPrice.toBigDecimal()
        offer.extTotalPrice = mmkOffer.price.toBigDecimal()

        val clientPrice =
            PriceCalculations.calculateClientPrice(
                mmkOffer.price.toBigDecimal(),
                yacht.agency!!.getDiscountOrZero(),
                yacht.excludeDiscount != true,
            )

        offer.clientPrice = clientPrice
        offer.agencyCommission = mmkOffer.price.toBigDecimal().minus(clientPrice)
        // Broker commission comes straight from the partner — MMK's
        // `commissionValue` is what we keep per offer (admin view / offer
        // workspace). Distinct from our own client-facing discount stored
        // above in `agencyCommission`.
        offer.brokerCommission = mmkOffer.commissionValue?.toBigDecimal()

        val obligatoryExtrasPrice =
            mmkOffer.obligatoryExtras
                ?.filter { it.payableInBase != true }
                ?.sumOf { it.price?.toBigDecimal() ?: BigDecimal.ZERO }
                ?: BigDecimal.ZERO
        val totalPrice = clientPrice + obligatoryExtrasPrice
        offer.totalPrice = totalPrice
        offer.obligatoryExtrasPrice = mmkOffer.obligatoryExtrasPrice?.toBigDecimal()

        offerRepository.save(offer)

        if (!mmkOffer.obligatoryExtras.isNullOrEmpty()) {
            handleObligatoryExtras(offer, mmkOffer)
        } else {
            offer.offerExtras.clear()
            offerRepository.save(offer)
        }

        // Partner payment plan = source of truth for installment timing.
        // Re-enabled 1.5.2026 — `ReservationPaymentPhasesService` now prefers
        // partner-side dates and applies B4Y discount proportionally to those
        // ratios. Empty/null plan → service falls back to internal A/B/C rules.
        if (!mmkOffer.paymentPlan.isNullOrEmpty()) {
            handlePaymentPlans(offer, mmkOffer)
        } else {
            offer.offerPaymentPlans.clear()
            offerRepository.save(offer)
        }
        return true
    }

    private fun handleObligatoryExtras(
        offer: Offer,
        mmkOffer: org.openapitools.client.mmk.model.Offer,
    ) {
        val allExtras = extraRepository.findAll()
        val allOfferExtras = offer.offerExtras
        val matchedIds = mutableSetOf<Long>()

        mmkOffer.obligatoryExtras?.forEach { mmkExtra ->
            val boat4youExtrasMatch =
                allExtras.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), mmkExtra.name)
                }

            val extraAlreadyOnOffer =
                allOfferExtras.find { ex ->
                    ex.id == mmkExtra.id
                }

            val mmkPrice = mmkExtra.price?.toBigDecimal()
            // See MmkYachtSyncService for rationale — MMK `payableInBase=false`
            // means "outside base", not "included". fromMmkPayableInBase
            // defaults non-crew extras to ON_SITE.
            val mmkPaymentType = ExtraPaymentType.fromMmkPayableInBase(
                name = mmkExtra.name,
                price = mmkPrice,
                payableInBase = mmkExtra.payableInBase ?: false,
            )
            if (extraAlreadyOnOffer != null) {
                extraAlreadyOnOffer.extras = boat4youExtrasMatch
                extraAlreadyOnOffer.price = mmkPrice
                extraAlreadyOnOffer.payableInBase = mmkExtra.payableInBase
                extraAlreadyOnOffer.paymentType = mmkPaymentType
                extraAlreadyOnOffer.description = mmkExtra.description?.takeIf { it.isNotBlank() }
                matchedIds.add(extraAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.offer = offer
            offerExtra.extras = boat4youExtrasMatch
            offerExtra.name = mmkExtra.name
            offerExtra.price = mmkPrice
            offerExtra.payableInBase = mmkExtra.payableInBase
            offerExtra.paymentType = mmkPaymentType
            offerExtra.obligatory = true
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = null
            offerExtra.externalId = mmkExtra.id // its extrasId, so duplicates are possible across different yachts
            offerExtra.description = mmkExtra.description?.takeIf { it.isNotBlank() }
            offer.offerExtras.add(offerExtra)
            offerExtraRepository.save(offerExtra)
            matchedIds.add(offerExtra.id!!)
        }

        val toRemove = mutableListOf<OfferExtra>()
        allOfferExtras.forEach { offerExtras ->
            if (matchedIds.none { it == offerExtras.id }) {
                toRemove.add(offerExtras)
            }
        }
        if (toRemove.isNotEmpty()) {
            offer.offerExtras.removeAll(toRemove)
            offerExtraRepository.deleteAll(toRemove)
        }
    }

    private fun handlePaymentPlans(
        offer: Offer,
        mmkOffer: org.openapitools.client.mmk.model.Offer,
    ) {
        // Create a set of dates from incoming payment plans for efficient lookup
        val incomingDates = mmkOffer.paymentPlan?.map { it.date?.value?.toLocalDate() }?.toSet() ?: emptySet()

        // Remove payment plans that are not in the incoming list
        offer.offerPaymentPlans.removeIf { existingPlan ->
            existingPlan.date !in incomingDates
        }

        // Add or update payment plans
        mmkOffer.paymentPlan?.forEach { incomingPlan ->
            val planDate = incomingPlan.date?.value?.toLocalDate()
            val existingPlan = offer.offerPaymentPlans.find { it.date == planDate }

            if (existingPlan != null) {
                // Update existing plan
                existingPlan.amount = incomingPlan.amount?.toBigDecimal()
            } else {
                // Add new plan
                val offerPaymentPlan = OfferPaymentPlan()
                offerPaymentPlan.offer = offer
                offerPaymentPlan.date = planDate
                offerPaymentPlan.amount = incomingPlan.amount?.toBigDecimal()
                offer.offerPaymentPlans.add(offerPaymentPlan)
            }
        }

        offerRepository.save(offer)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun syncOffers(mmkAllOffers: List<org.openapitools.client.mmk.model.Offer>) {
        val yachtExternalIds = mmkAllOffers.map { it.yachtId }.distinct()
        val allYachts =
            yachtRepository.findByExternalIdsAndExternalSystemId(
                yachtExternalIds,
                ExternalSystemEnum.MMK.value.toLong(),
            )
        val allYachtMappings =
            externalMappingService.findAllByTypeAndExternalSystemAndExternalIdIn(
                Yacht::class.simpleName.toString(),
                ExternalSystemEnum.MMK.value,
                yachtExternalIds,
            )

        mmkAllOffers.groupBy { it.yachtId }.forEach { (mmkYachyId, mmkOffers) ->
            val mapping = allYachtMappings.find { it.externalId == mmkYachyId!! } ?: return@forEach
            val yacht = allYachts.find { y -> y.id == mapping.systemId } ?: return@forEach

            val minDateFrom = mmkOffers.minOfOrNull { it.dateFrom.value!!.toLocalDate() }
            val maxDateTo = mmkOffers.maxOfOrNull { it.dateTo.value!!.toLocalDate() }
            val allDatesEqual =
                mmkOffers.all { it.dateFrom.value!!.toLocalDate() == minDateFrom && it.dateTo.value!!.toLocalDate() == maxDateTo }

            // use for better db index usage
            val existingYachtOffers =
                if (allDatesEqual) {
                    offerRepository.findAllByYachtAndDateFromAndDateTo(yacht, minDateFrom!!, maxDateTo!!)
                } else {
                    offerRepository.findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual(
                        yacht,
                        minDateFrom!!,
                        maxDateTo!!,
                    )
                }

            mmkOffers.forEach { mmkOffer ->
                val existingOffer =
                    existingYachtOffers.find {
                        it.dateFrom == mmkOffer.dateFrom.value?.toLocalDate() &&
                            it.dateTo == mmkOffer.dateTo.value?.toLocalDate() &&
                            it.product == CharterType.fromMmkValue(mmkOffer.product)
                    }
                if (existingOffer == null) {
                    updateOffer(Offer(), yacht, mmkOffer)
                } else {
                    updateOffer(existingOffer, yacht, mmkOffer)
                }
            }
        }
    }
}
