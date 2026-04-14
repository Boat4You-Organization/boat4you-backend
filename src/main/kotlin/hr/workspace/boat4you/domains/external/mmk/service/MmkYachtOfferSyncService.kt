package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
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

        mmkOffers.groupBy { it.yachtId }.forEach { (yachtId, mmkOffers) ->
            val mapping = allMappings.find { it.externalId == yachtId!!.toLong() }
            val yacht = allAgencyYachts.find { it.id == mapping!!.systemId!! }!!

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
                if (existingOffer == null) {
                    updateOffer(Offer(), yacht, mmkOffer)
                } else {
                    updateOffer(existingOffer, yacht, mmkOffer)
                    syncedOffers.add(existingOffer.id!!)
                }
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
    }

    private fun updateOffer(
        offer: Offer,
        yacht: Yacht,
        mmkOffer: org.openapitools.client.mmk.model.Offer,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        val locationFromMapping =
            allLocationMappings.find { location -> location.externalId == mmkOffer.startBaseId }
        val locationFrom = locationQueryingService.getCachedLocationById(locationFromMapping!!.systemId!!)
        val locationToMapping =
            allLocationMappings.find { location -> location.externalId == mmkOffer.endBaseId }
        val locationTo = locationQueryingService.getCachedLocationById(locationToMapping!!.systemId!!)

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

        // TODO For now just skipped as we don't need external payment plans
//        if (!mmkOffer.paymentPlan.isNullOrEmpty()) {
//            handlePaymentPlans(offer, mmkOffer)
//        } else {
//            offer.offerPaymentPlans.clear()
//            offerRepository.save(offer)
//        }
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

            if (extraAlreadyOnOffer != null) {
                extraAlreadyOnOffer.extras = boat4youExtrasMatch
                extraAlreadyOnOffer.price = mmkExtra.price?.toBigDecimal()
                extraAlreadyOnOffer.payableInBase = mmkExtra.payableInBase
                matchedIds.add(extraAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.offer = offer
            offerExtra.extras = boat4youExtrasMatch
            offerExtra.name = mmkExtra.name
            offerExtra.price = mmkExtra.price?.toBigDecimal()
            offerExtra.payableInBase = mmkExtra.payableInBase
            offerExtra.obligatory = true
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = null
            offerExtra.externalId = mmkExtra.id // its extrasId, so duplicates are possible across different yachts
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
