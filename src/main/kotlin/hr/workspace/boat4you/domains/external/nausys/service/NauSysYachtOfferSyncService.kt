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

        nausysOffers.freeYachts?.groupBy { it.yachtId }?.forEach { (yachtId, nausysYachtOffers) ->
            val mapping = allMappings.find { it.externalId == yachtId!! }
            val yacht = allAgencyYachts.find { it.id == mapping!!.systemId!! }!!

            val existingYachtOffers = getOffersForYacht(yacht, nausysYachtOffers)

            if (existingYachtOffers.isEmpty()) {
                nausysYachtOffers.forEach { nausysOffer ->
                    updateOffer(Offer(), yacht, nausysOffer, allLocationMappings)
                }
            } else {
                nausysYachtOffers.forEach { nausysOffer ->
                    val existingOffer =
                        existingYachtOffers.find { it.dateFrom == nausysOffer.periodFrom!!.value && it.dateTo == nausysOffer.periodTo!!.value }
                    if (existingOffer == null) {
                        updateOffer(Offer(), yacht, nausysOffer, allLocationMappings)
                    } else {
                        updateOffer(existingOffer, yacht, nausysOffer, allLocationMappings)
                        syncedOffers.add(existingOffer.id!!)
                    }
                }
            }

            // Remove offers that are not in the new list
            // this is needed because syncOffers will be called for each yacht multiple times for different dates
            existingYachtOffers
                .filter { it.dateFrom == dateFrom && it.dateTo == dateTo }
                .filter { !syncedOffers.contains(it.id!!) && it.status != OfferStatus.UNAVAILABLE }
                .forEach { offer ->
                    offer.status = OfferStatus.UNAVAILABLE
                    offerRepository.save(offer)
                }
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
                if (existingOffer == null) {
                    updateOffer(Offer(), yacht, nausysOffer, allLocationMappings)
                } else {
                    updateOffer(existingOffer, yacht, nausysOffer, allLocationMappings)
                }
            }
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

    private fun updateOffer(
        offer: Offer,
        yacht: Yacht,
        nausysOffer: RestFreeYacht,
        allLocationMappings: List<ExternalMapping>,
    ) {
        val locationFromMapping =
            allLocationMappings.find { location -> location.externalId == nausysOffer.locationFromId!!.toLong() }
        val locationFrom = locationQueryingService.getCachedLocationById(locationFromMapping!!.systemId!!)
        val locationToMapping =
            allLocationMappings.find { location -> location.externalId == nausysOffer.locationToId!!.toLong() }
        val locationTo = locationQueryingService.getCachedLocationById(locationToMapping!!.systemId!!)

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

        val obligatoryExtrasPrice =
            nausysOffer.obligatoryExtras
                ?.filter { it.calculationType?.value != "SEPARATE_PAYMENT" }
                ?.sumOf { it.totalPrice?.toBigDecimal() ?: BigDecimal.ZERO }
                ?: BigDecimal.ZERO
        offer.totalPrice = clientPrice + obligatoryExtrasPrice
        offer.obligatoryExtrasPrice = obligatoryExtrasPrice

        offerRepository.save(offer)

        handleExtras(offer, nausysOffer)

        // TODO For now just skipped as we don't need external payment plans
//        if (!nausysOffer.paymentPlans.isNullOrEmpty()) {
//            handlePaymentPlans(offer, nausysOffer)
//        } else {
//            offer.offerPaymentPlans.clear()
//            offerRepository.save(offer)
//        }
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

            val equipmentAlreadyOnOffer =
                offer.offerExtras.find { ex ->
                    ex.externalId == nausysExtra.id
                }

            if (equipmentAlreadyOnOffer != null) {
                equipmentAlreadyOnOffer.extras = boat4youEquipmentMatch
                equipmentAlreadyOnOffer.price = nausysExtra.totalPrice?.toBigDecimal()
                equipmentAlreadyOnOffer.payableInBase = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
                equipmentAlreadyOnOffer.externalUnit = nausysExtra.priceMeasureId.toString()
                matchedIds.add(equipmentAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.extras = boat4youEquipmentMatch
            offerExtra.name = externalEquipmentMatch.name
            offerExtra.externalId = nausysExtra.id!!
            offerExtra.offer = offer
            offerExtra.price = nausysExtra.totalPrice?.toBigDecimal()
            offerExtra.payableInBase = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = nausysExtra.priceMeasureId.toString()
            offerExtra.obligatory = true

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

            val equipmentAlreadyOnOffer =
                offer.offerExtras.find { ex ->
                    ex.externalId == nausysExtra.id
                }

            if (equipmentAlreadyOnOffer != null) {
                equipmentAlreadyOnOffer.extras = boat4youEquipmentMatch
                equipmentAlreadyOnOffer.price = nausysExtra.totalPrice?.toBigDecimal()
                equipmentAlreadyOnOffer.payableInBase = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
                equipmentAlreadyOnOffer.externalUnit = nausysExtra.priceMeasureId.toString()
                matchedIds.add(equipmentAlreadyOnOffer.id!!)
                return@forEach
            }

            val offerExtra = OfferExtra()
            offerExtra.extras = boat4youEquipmentMatch
            offerExtra.name = externalEquipmentMatch.name
            offerExtra.externalId = nausysExtra.id!!
            offerExtra.offer = offer
            offerExtra.price = nausysExtra.totalPrice?.toBigDecimal()
            offerExtra.payableInBase = nausysExtra.calculationType?.value == "SEPARATE_PAYMENT"
            offerExtra.unit = ExtrasUnitType.PER_BOOKING
            offerExtra.externalUnit = nausysExtra.priceMeasureId.toString()
            offerExtra.obligatory = false

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

            val amount =
                incomingPlan.percentage?.let {
                    it.multiply(
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
