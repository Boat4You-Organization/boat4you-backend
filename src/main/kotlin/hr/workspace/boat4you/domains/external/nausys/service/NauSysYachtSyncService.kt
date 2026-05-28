package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSeasonRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LanguageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ReservationOption
import hr.workspace.boat4you.domains.catalouge.jpa.ReservationOptionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtCharterType
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslation
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import hr.workspace.boat4you.domains.external.utils.Matchers
import org.openapitools.client.nausys.model.RestInternationalText
import org.openapitools.client.nausys.model.RestYacht
import org.openapitools.client.nausys.model.RestYachtCheckInPeriod
import org.openapitools.client.nausys.model.RestYachtList
import org.openapitools.client.nausys.model.RestYachtPicture
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.collections.forEach

@Service
@Transactional(readOnly = true)
class NauSysYachtSyncService(
    private val yachtRepository: YachtRepository,
    private val externalMappingRepository: ExternalMappingRepository,
    private val externalSystemService: ExternalSystemService,
    private val modelRepository: ModelRepository,
    private val externalMappingService: ExternalMappingService,
    private val yachtImageRepository: YachtImageRepository,
    private val reservationOptionRepository: ReservationOptionRepository,
    private val yachtEquipmentRepository: YachtEquipmentRepository,
    private val equipmentRepository: EquipmentRepository,
    private val locationQueryingService: LocationQueryingService,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val yachtExtraRepository: YachtExtraRepository,
    private val extraRepository: ExtraRepository,
    private val languageRepository: LanguageRepository,
    private val yachtTranslationRepository: YachtTranslationRepository,
    private val fileSystemService: FileSystemService,
    private val agencyRepository: AgencyRepository,
    private val externalSeasonRepository: ExternalSeasonRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional
    fun syncYachtsForAgency(
        agencyId: Long,
        nausysResponse: RestYachtList,
    ) {
        val agency =
            agencyRepository
                .findById(agencyId)
                .orElseThrow { IllegalArgumentException("Agency with id $agencyId not found") }
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
            )
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)

        nausysResponse.yachts?.forEach { nausysYacht ->
            val mapping = allMappings.find { mapping -> mapping.externalId == nausysYacht.id!!.toLong() }

            var isNewEntity = false
            val yacht =
                if (mapping != null) {
                    yachtRepository.findById(mapping.systemId!!).get()
                } else {
                    isNewEntity = true
                    Yacht()
                }

            val model = getModel(nausysYacht)
            if (model == null) {
                // Partner exposes the yacht but hasn't populated yachtModelId
                // yet (Istion adds shell records before finishing metadata —
                // 2026-05-28 observation). Track it via external_mapping +
                // a stub Yacht row so the next sync, once the partner fills
                // model in, can complete it in-place instead of treating it
                // as never-seen. Keep sys_active=false so the listing
                // doesn't surface a half-empty card; updateFromNausysModel
                // flips it back to true on the next iteration after model
                // resolves.
                yacht.name = nausysYacht.name
                yacht.agency = agency
                yacht.entryType = EntryType.EXTERNAL
                yacht.buildYear = nausysYacht.buildYear?.toShort()
                yacht.sysActive = false
                val saved = yachtRepository.saveAndFlush(yacht)
                if (mapping == null) {
                    externalMappingRepository.save(
                        ExternalMapping(
                            externalId = nausysYacht.id!!.toLong(),
                            externalSystem = externalSystem,
                            systemId = saved.id!!,
                            type = Yacht::class.simpleName.toString(),
                            extendedType = YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
                        ),
                    )
                }
                log.info(
                    "NauSys yacht ${nausysYacht.id} (${nausysYacht.name}) tracked as shell " +
                        "(yachtModelId=${nausysYacht.yachtModelId} unresolvable); " +
                        "will activate once partner populates model.",
                )
                return@forEach
            }
            if (shouldSkip(nausysYacht, model)) {
                return@forEach
            }

            val updatedYacht = updateFromNausysModel(yacht, nausysYacht, agency, allLocationMappings, model)

            nausysYacht.highlightsIntText?.let { createTranslations(updatedYacht, it, TranslationType.DESCRIPTION) }

            syncPictures(nausysYacht.pictures, isNewEntity, updatedYacht)
            yacht.mainImageId = getMainImage(updatedYacht)?.id
            syncEquipment(updatedYacht, nausysYacht)
            syncExtras(updatedYacht, nausysYacht)
            syncReservationOptions(updatedYacht, nausysYacht.checkInPeriods)

            yachtRepository.saveAndFlush(updatedYacht)

            if (mapping == null) {
                externalMappingRepository.save(
                    ExternalMapping(
                        externalId = nausysYacht.id!!.toLong(),
                        externalSystem = externalSystem,
                        systemId = updatedYacht.id!!,
                        type = Yacht::class.simpleName.toString(),
                        extendedType = YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
                    ),
                )
            }
        }
    }

    fun deactivateYachtsForAgency(
        agencyId: Long,
        allYachts: List<Long>,
    ) {
        // Safety guard: if the partner response was empty (transient outage,
        // auth failure, partner-side maintenance), refuse to deactivate ALL
        // of the agency's yachts. The catalogue almost always carries >0
        // entries; an empty list almost always means a flaky upstream call,
        // not a partner emptying their fleet. The next successful sync run
        // takes care of any genuine removals.
        if (allYachts.isEmpty()) {
            log.warn(
                "Deactivation aborted for agency $agencyId: partner returned 0 yachts " +
                    "(treating as suspected upstream glitch).",
            )
            return
        }

        val agency =
            agencyRepository
                .findById(agencyId)
                .orElseThrow { IllegalArgumentException("Agency with id $agencyId not found") }

        val yachtsToDeactivate = yachtRepository.findAllByAgencyAndExternalIdNotIn(agency, allYachts)
        log.warn("Deactivating ${yachtsToDeactivate.size} yachts for agency ${agency.id} ${agency.name}")
        yachtsToDeactivate.forEach {
            it.sysActive = false
            yachtRepository.save(it)
        }
    }

    private fun updateFromNausysModel(
        yacht: Yacht,
        nausysYacht: RestYacht,
        agency: Agency,
        allLocationMappings: List<ExternalMapping>,
        model: Model,
    ): Yacht {
        val locationMapping =
            allLocationMappings.find { location -> location.externalId == nausysYacht.locationId!!.toLong() }
        val location = locationQueryingService.getCachedLocationById(locationMapping!!.systemId!!)

        yacht.agency = agency
        yacht.location = location
        yacht.model = model
        yacht.name = nausysYacht.name
        yacht.deposit = nausysYacht.deposit
        yacht.insuredDeposit = nausysYacht.depositWhenInsured
        yacht.depositCurrency = nausysYacht.depositCurrency
        yacht.buildYear = nausysYacht.buildYear?.toShort()
        yacht.launchYear = nausysYacht.launchedYear?.toShort()
        yacht.enginePower = calcEnginePower(nausysYacht.enginePower?.toShort(), nausysYacht.engines?.toShort())
        yacht.draught = nausysYacht.draft
        // RestYacht carries no beam — Nausys stores it on RestYachtModel, copied
        // into Model.beam by the catalogue sync. Take it from there so Nausys
        // yachts surface the same beam shown on Nausys's own dashboard.
        yacht.beam = model.beam
        yacht.waterTank = nausysYacht.waterTank
        yacht.fuelTank = nausysYacht.fuelTank
        yacht.cabins = nausysYacht.cabins?.toShort()
        yacht.crewCabins = nausysYacht.cabinsCrew?.toShort()
        yacht.wc = nausysYacht.wc?.toShort()
        yacht.crewWc = nausysYacht.wc?.toShort()
        yacht.berths = nausysYacht.berthsTotal?.toShort()
        yacht.crewBerths = nausysYacht.berthsCrew?.toShort()
        yacht.maxPersons = nausysYacht.maxPersons?.toShort()
        yacht.defaultCheckin = nausysYacht.checkInTime
        yacht.defaultCheckout = nausysYacht.checkOutTime
        yacht.mainsailType = SailTypeEnum.fromNausysValue(nausysYacht.sailTypeId)
        yacht.mainsailArea = null // MMK only
        yacht.genoaType = SailTypeEnum.fromNausysValue(nausysYacht.genoaTypeId)
        yacht.genoaArea = null // MMK only
        yacht.registrationNumber = nausysYacht.registrationNumber
        yacht.optionApproval = nausysYacht.needsOptionApproval
        yacht.optionToReservation = nausysYacht.canMakeBookingFixed
        yacht.commision = nausysYacht.commission
        yacht.commisionPerc = null // not in nausys
        yacht.maxDiscount = nausysYacht.maxDiscount
        yacht.maxDiscountFromCommision = nausysYacht.maxDiscountFromCommission
        yacht.agencyDiscountType = nausysYacht.agencyDiscountType
        // Same reason as beam — length lives on RestYachtModel (loa), not RestYacht.
        yacht.length = model.length
        yacht.crewNumber = nausysYacht.crewCount?.toShort()

        yacht.entryType = EntryType.EXTERNAL
        yacht.extCharterType = nausysYacht.charterType
        syncCharterTypes(yacht, nausysYacht)
        yacht.extCrewedType = nausysYacht.crewedCharterType

        yacht.vesselType = VesselType.fromNauSysCategoryId(model.externalCategoryId!!)

        yacht.sysActive = true
        // we don't update fileds: excludeDiscount
        return yachtRepository.save(yacht)
    }

    private fun syncCharterTypes(
        yacht: Yacht,
        nausysYacht: RestYacht,
    ) {
        val yachtCharterTypes = yacht.yachtCharterTypes
        val charterType = CharterType.fromNausysValue(nausysYacht.charterType)
        val isAllInclusive = nausysYacht.crewedCharterType == "ALL_INCLUSIVE" && charterType == CharterType.CREWED
        val types =
            if (isAllInclusive) {
                setOf(charterType, CharterType.ALL_INCLUSIVE)
            } else {
                setOf(charterType)
            }

        if (yachtCharterTypes.map { it.type }.containsAll(types)) {
            return
        }

        when (charterType) {
            CharterType.BAREBOAT -> {
                yachtCharterTypes.removeIf { it.type != CharterType.BAREBOAT }
                if (yachtCharterTypes.none { it.type == CharterType.BAREBOAT }) {
                    yachtCharterTypes.add(YachtCharterType(yacht = yacht, type = CharterType.BAREBOAT))
                }
            }

            CharterType.CREWED -> {
                if (isAllInclusive) {
                    yachtCharterTypes.removeIf { it.type != CharterType.ALL_INCLUSIVE || it.type != CharterType.CREWED }
                    if (yachtCharterTypes.none { it.type == CharterType.ALL_INCLUSIVE }) {
                        yachtCharterTypes.add(YachtCharterType(yacht = yacht, type = CharterType.ALL_INCLUSIVE))
                    }
                    if (yachtCharterTypes.none { it.type == CharterType.CREWED }) {
                        yachtCharterTypes.add(YachtCharterType(yacht = yacht, type = CharterType.CREWED))
                    }
                } else {
                    yachtCharterTypes.removeIf { it.type != CharterType.CREWED }
                    if (yachtCharterTypes.none { it.type == CharterType.CREWED }) {
                        yachtCharterTypes.add(YachtCharterType(yacht = yacht, type = CharterType.CREWED))
                    }
                }
            }

            else -> {
                log.warn("Unknown charter type: ${nausysYacht.charterType}")
                return
            }
        }
    }

    private fun syncReservationOptions(
        yacht: Yacht,
        checkInPeriods: List<RestYachtCheckInPeriod>?,
    ) {
        val allOptions = reservationOptionRepository.findAllByYacht(yacht)
        checkInPeriods?.forEach { checkInPeriod ->
            val existing =
                allOptions.find { option -> option.dateFrom == checkInPeriod.dateFrom?.value && option.dateTo == checkInPeriod.dateTo?.value }
            val reservationOption = existing ?: ReservationOption()

            if (existing == null) {
                reservationOption.yacht = yacht
                reservationOption.dateFrom = checkInPeriod.dateFrom?.value
                reservationOption.dateTo = checkInPeriod.dateTo?.value
            }

            reservationOption.minimalDuration = checkInPeriod.minimalReservationDuration?.toShort() ?: 7
            reservationOption.checkinMon = checkInPeriod.checkInMonday
            reservationOption.checkinTue = checkInPeriod.checkInTuesday
            reservationOption.checkinWed = checkInPeriod.checkInWednesday
            reservationOption.checkinThu = checkInPeriod.checkInThursday
            reservationOption.checkinFri = checkInPeriod.checkInFriday
            reservationOption.checkinSat = checkInPeriod.checkInSaturday
            reservationOption.checkinSun = checkInPeriod.checkOutFriday
            reservationOption.checkoutMon = checkInPeriod.checkOutMonday
            reservationOption.checkoutTue = checkInPeriod.checkOutTuesday
            reservationOption.checkoutWed = checkInPeriod.checkOutWednesday
            reservationOption.checkoutThu = checkInPeriod.checkOutThursday
            reservationOption.checkoutFri = checkInPeriod.checkOutFriday
            reservationOption.checkoutSat = checkInPeriod.checkOutSaturday
            reservationOption.checkoutSun = checkInPeriod.checkOutSunday
            reservationOptionRepository.save(reservationOption)
        }
    }

    private fun syncPictures(
        nausysPictures: List<RestYachtPicture>?,
        isNewEntity: Boolean,
        yacht: Yacht,
    ) {
        if (nausysPictures.isNullOrEmpty()) {
            return
        }
        if (isNewEntity) {
            nausysPictures.forEachIndexed { index, restYachtPicture ->
                createNewYachtImage(yacht, index, restYachtPicture)
            }
        } else {
            val allYachImages = yacht.yachtImages
            nausysPictures.forEachIndexed { index, nausysPicture ->
                val yachtImage = allYachImages.find { it.externalUrl == nausysPicture.src }
                if (yachtImage == null) {
                    createNewYachtImage(yacht, index, nausysPicture)
                } else {
                    yachtImage.position = index.toShort()
                    yachtImage.mainImage = nausysPicture.mainPicture
                    yachtImageRepository.save(yachtImage)
                }
            }
            // delete images that are not in the list
            val toRemove = mutableListOf<YachtImage>()
            allYachImages.forEach { yachtImage ->
                if (nausysPictures.none { it.src == yachtImage.externalUrl }) {
                    yachtImage.url?.let { fileSystemService.deleteFile(it) }
                    toRemove.add(yachtImage)
                }
            }
            if (toRemove.isNotEmpty()) {
                yacht.yachtImages.removeAll(toRemove)
                yachtImageRepository.deleteAll(toRemove)
            }
        }
    }

    private fun createNewYachtImage(
        yacht: Yacht,
        index: Int,
        restYachtPicture: RestYachtPicture,
    ) {
        try {
            if (restYachtPicture.src.isNullOrBlank()) {
                return
            }
            val newYachtImage = YachtImage()
            newYachtImage.yacht = yacht
            newYachtImage.url = null
            newYachtImage.externalUrl = restYachtPicture.src
            newYachtImage.position = index.toShort()
            newYachtImage.mainImage = restYachtPicture.mainPicture
            newYachtImage.synced = false
            yacht.yachtImages.add(newYachtImage)
            yachtImageRepository.save(newYachtImage)
        } catch (e: Exception) {
            log.error("Failed to create yacht image for yacht ${yacht.id} at index $index: ${e.message}")
        }
    }

    private fun getMainImage(yacht: Yacht): YachtImage? {
        return yacht.yachtImages.firstOrNull { it.mainImage == true } ?: yacht.yachtImages.firstOrNull()
    }

    private fun syncEquipment(
        yacht: Yacht,
        nausysYacht: RestYacht,
    ) {
        if (nausysYacht.standardYachtEquipment.isNullOrEmpty()) {
            return
        }

        val allYachtEquipment = yacht.yachtEquipments
        val allEquipment = equipmentRepository.findAll()
        val allExternalEquipment =
            externalEquipmentRepository.getCachedByExternalSystemId(ExternalSystemEnum.NAUSYS.value)
        val matchedIds = mutableSetOf<Long>()

        nausysYacht.standardYachtEquipment?.forEach { nausysEquipment ->
            // for each nausys equipment, find the external nausys equipment and try to match it with our equipment
            val externalEquipmentMatch =
                allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysEquipment.equipmentId!!.toLong() && eq.type == ExternalEquipmentType.EQUIPMENT }
            if (externalEquipmentMatch == null) {
                log.error("Nausys equipment not found in Nausys external equipment: $nausysEquipment")
                return@forEach
            }
            val boat4youEquipmentMatch =
                allEquipment.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                }

            // Dedup primarily on partner equipmentId (yacht_equipment.external_id
            // is set to externalEquipmentMatch.externalId at insert time). Falls
            // back to name match for safety. The pre-fix `eq.equipment?.id ==
            // boat4youEquipmentMatch?.id` clause silently collapsed all
            // unmatched (boat4youMatch=null) partner items onto a single
            // pre-existing unmatched yacht_equipment row because `null == null`
            // returned true — that's why yacht 13175 stayed at 16 items even
            // though the partner ships 80.
            val equipmentAlreadyOnYacht =
                allYachtEquipment.find { eq ->
                    eq.externalId == externalEquipmentMatch.externalId ||
                        eq.name == externalEquipmentMatch.name
                }

            // NauSys ships highlight/quantity/comment per-item; sync them every
            // pass so partner edits propagate. comment is multilingual on the
            // partner side — for now persist the EN text and fall back to the
            // first non-null variant if EN is empty.
            val partnerHighlight = nausysEquipment.highlight ?: false
            val partnerQuantity =
                nausysEquipment.quantity?.takeIf { it.compareTo(java.math.BigDecimal.ONE) != 0 }
            val partnerComment =
                nausysEquipment.comment?.let { c ->
                    c.textEN ?: c.textDE ?: c.textHR ?: c.textIT ?: c.textFR ?: c.textES
                }?.takeIf { it.isNotBlank() }

            if (equipmentAlreadyOnYacht != null) {
                // change equipment match
                if (equipmentAlreadyOnYacht.equipment == null && boat4youEquipmentMatch != null) {
                    equipmentAlreadyOnYacht.equipment = boat4youEquipmentMatch
                }
                equipmentAlreadyOnYacht.highlight = partnerHighlight
                equipmentAlreadyOnYacht.quantity = partnerQuantity
                equipmentAlreadyOnYacht.comment = partnerComment
                yachtEquipmentRepository.save(equipmentAlreadyOnYacht)

                matchedIds.add(equipmentAlreadyOnYacht.id!!)
                return@forEach
            }

            val yachtEquipment = YachtEquipment()
            yachtEquipment.equipment = boat4youEquipmentMatch
            yachtEquipment.name = externalEquipmentMatch.name
            yachtEquipment.externalId = externalEquipmentMatch.externalId!!
            yachtEquipment.yacht = yacht
            yachtEquipment.highlight = partnerHighlight
            yachtEquipment.quantity = partnerQuantity
            yachtEquipment.comment = partnerComment
            yachtEquipmentRepository.save(yachtEquipment)

            yacht.yachtEquipments.add(yachtEquipment)
            matchedIds.add(yachtEquipment.id!!)
        }

        val toRemove = mutableListOf<YachtEquipment>()
        allYachtEquipment.forEach { yachtEquipment ->
            if (matchedIds.none { it == yachtEquipment.id }) {
                toRemove.add(yachtEquipment)
            }
        }
        if (toRemove.isNotEmpty()) {
            yacht.yachtEquipments.removeAll(toRemove)
            yachtEquipmentRepository.deleteAll(toRemove)
        }
    }

    private fun syncExtras(
        yacht: Yacht,
        nausysYacht: RestYacht,
    ) {
        if (nausysYacht.seasonSpecificData.isNullOrEmpty()) {
            return
        }

        val allYachtExtras = yacht.yachtExtras
        val allExtras = extraRepository.findAll()
        val allExternalEquipment =
            externalEquipmentRepository.getCachedByExternalSystemId(ExternalSystemEnum.NAUSYS.value)
        val matchedIds = mutableSetOf<Long>()

        nausysYacht.seasonSpecificData?.forEach { season ->
            val externalSeason = externalSeasonRepository.findByExternalId(season.seasonId!!)
            if (externalSeason == null) {
                log.warn("Nausys season not found in Nausys external seasons: ${season.seasonId}")
            }
            season.additionalYachtEquipment?.forEach { nausysEquipment ->
                // for each nausys equipment, find the external nausys equipment and try to match it with our equipment
                val externalEquipmentMatch =
                    allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysEquipment.equipmentId!! && eq.type == ExternalEquipmentType.EQUIPMENT }
                if (externalEquipmentMatch == null) {
                    log.warn("Nausys equipment not found in Nausys external equipment: $nausysEquipment")
                    return@forEach
                }
                val boat4youMatch =
                    allExtras.firstOrNull { eq ->
                        Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                    }

                val equipmentAlreadyOnYacht =
                    allYachtExtras.find { ex ->
                        ex.externalId == nausysEquipment.id
                    }

                val nausysEqPrice = if (nausysEquipment.amountIsPercentage != true) nausysEquipment.amount?.toBigDecimal() else BigDecimal.ZERO
                val nausysEqPayable = nausysEquipment.calculationType == "SEPARATE_PAYMENT"
                val nausysEqPaymentType = hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.fromNausysCalculationType(
                    calculationType = nausysEquipment.calculationType,
                    price = nausysEqPrice,
                )
                if (equipmentAlreadyOnYacht != null) {
                    equipmentAlreadyOnYacht.extras = boat4youMatch
                    equipmentAlreadyOnYacht.price = nausysEqPrice
                    equipmentAlreadyOnYacht.payableInBase = nausysEqPayable
                    equipmentAlreadyOnYacht.paymentType = nausysEqPaymentType
                    equipmentAlreadyOnYacht.unit = ExtrasUnitType.fromNausysValue(nausysEquipment.priceMeasureId)
                    equipmentAlreadyOnYacht.externalUnit = nausysEquipment.priceMeasureId.toString()
                    equipmentAlreadyOnYacht.validFrom = externalSeason?.validFrom
                    equipmentAlreadyOnYacht.validTo = externalSeason?.validTo
                    equipmentAlreadyOnYacht.validForBases = nausysEquipment.validForBases
                    equipmentAlreadyOnYacht.description = nausysEquipment.comment?.let { it.textEN ?: it.textHR ?: it.textIT ?: it.textDE }?.takeIf { it.isNotBlank() }
                    matchedIds.add(equipmentAlreadyOnYacht.id!!)
                    return@forEach
                }

                val yachtExtra = YachtExtra()
                yachtExtra.extras = boat4youMatch
                yachtExtra.name = externalEquipmentMatch.name
                yachtExtra.externalId = nausysEquipment.id
                yachtExtra.yacht = yacht
                yachtExtra.price = nausysEqPrice
                yachtExtra.payableInBase = nausysEqPayable
                yachtExtra.paymentType = nausysEqPaymentType
                yachtExtra.unit = ExtrasUnitType.fromNausysValue(nausysEquipment.priceMeasureId)
                yachtExtra.externalUnit = nausysEquipment.priceMeasureId.toString()
                yachtExtra.obligatory = false
                yachtExtra.validFrom = externalSeason?.validFrom
                yachtExtra.validTo = externalSeason?.validTo
                yachtExtra.type = ExtrasType.EQUIPMENT
                yachtExtra.validForBases = nausysEquipment.validForBases
                yachtExtra.description = nausysEquipment.comment?.let { it.textEN ?: it.textHR ?: it.textIT ?: it.textDE }?.takeIf { it.isNotBlank() }

                yachtExtraRepository.save(yachtExtra)
                yacht.yachtExtras.add(yachtExtra)
                matchedIds.add(yachtExtra.id!!)
            }
            season.services?.forEach { nausysService ->
                val externalEquipmentMatch =
                    allExternalEquipment.firstOrNull { eq -> eq.externalId == nausysService.serviceId!! && eq.type == ExternalEquipmentType.SERVICE }
                if (externalEquipmentMatch == null) {
                    log.warn("Nausys service not found in Nausys external equipment: $nausysService")
                    return@forEach
                }
                val boat4youMatch =
                    allExtras.firstOrNull { eq ->
                        Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                    }

                val equipmentAlreadyOnYacht =
                    allYachtExtras.find { ex ->
                        ex.externalId == nausysService.id
                    }

                val nausysSvcPrice = if (nausysService.amountIsPercentage != true) nausysService.amount?.toBigDecimal() else BigDecimal.ZERO
                val nausysSvcPayable = nausysService.calculationType == "SEPARATE_PAYMENT"
                val nausysSvcPaymentType = hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType.fromNausysCalculationType(
                    calculationType = nausysService.calculationType,
                    price = nausysSvcPrice,
                )
                if (equipmentAlreadyOnYacht != null) {
                    equipmentAlreadyOnYacht.extras = boat4youMatch
                    equipmentAlreadyOnYacht.price = nausysSvcPrice
                    equipmentAlreadyOnYacht.payableInBase = nausysSvcPayable
                    equipmentAlreadyOnYacht.paymentType = nausysSvcPaymentType
                    equipmentAlreadyOnYacht.unit = ExtrasUnitType.fromNausysValue(nausysService.priceMeasureId)
                    equipmentAlreadyOnYacht.externalUnit = nausysService.priceMeasureId.toString()
                    equipmentAlreadyOnYacht.obligatory = nausysService.obligatory
                    equipmentAlreadyOnYacht.validFrom = nausysService.validPeriodFrom?.value ?: externalSeason?.validFrom
                    equipmentAlreadyOnYacht.validTo = nausysService.validPeriodTo?.value ?: externalSeason?.validTo
                    equipmentAlreadyOnYacht.validForBases = nausysService.validForBases
                    equipmentAlreadyOnYacht.description = nausysService.description?.let { it.textEN ?: it.textHR ?: it.textIT ?: it.textDE }?.takeIf { it.isNotBlank() }
                    matchedIds.add(equipmentAlreadyOnYacht.id!!)
                    return@forEach
                }

                val yachtExtra = YachtExtra()
                yachtExtra.extras = boat4youMatch
                yachtExtra.name = externalEquipmentMatch.name
                yachtExtra.externalId = nausysService.id
                yachtExtra.yacht = yacht
                yachtExtra.price = nausysSvcPrice
                yachtExtra.payableInBase = nausysSvcPayable
                yachtExtra.paymentType = nausysSvcPaymentType
                yachtExtra.unit = ExtrasUnitType.fromNausysValue(nausysService.priceMeasureId)
                yachtExtra.externalUnit = nausysService.priceMeasureId.toString()
                yachtExtra.obligatory = nausysService.obligatory
                yachtExtra.validFrom = nausysService.validPeriodFrom?.value ?: externalSeason?.validFrom
                yachtExtra.validTo = nausysService.validPeriodTo?.value ?: externalSeason?.validTo
                yachtExtra.type = ExtrasType.EXTRAS
                yachtExtra.validForBases = nausysService.validForBases
                yachtExtra.description = nausysService.description?.let { it.textEN ?: it.textHR ?: it.textIT ?: it.textDE }?.takeIf { it.isNotBlank() }

                yachtExtraRepository.save(yachtExtra)
                yacht.yachtExtras.add(yachtExtra)
                matchedIds.add(yachtExtra.id!!)
            }
        }

        val toRemove = mutableListOf<YachtExtra>()
        allYachtExtras.forEach { yachtExtras ->
            if (matchedIds.none { it == yachtExtras.id }) {
                toRemove.add(yachtExtras)
            }
        }
        if (toRemove.isNotEmpty()) {
            yacht.yachtExtras.removeAll(toRemove)
            yachtExtraRepository.deleteAll(toRemove)
        }
    }

    private fun getModel(nausysYacht: RestYacht): Model? {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allModelMappings =
            externalMappingService.getCachedAllMappingsByType(Model::class.simpleName.toString(), externalSystem)

        val modelMapping = allModelMappings.find { it.externalId == nausysYacht.yachtModelId } ?: return null
        // Manufacturer/Model dedup migrations (27.4.2026) deleted Model rows
        // without rewriting the matching `external_mapping` entries, so a
        // mapping can point at a now-missing Model id. .orElse(null) drops
        // the whole agency-level transaction back to the caller, which we
        // already log + skip per-yacht.
        return modelRepository.findById(modelMapping.systemId!!).orElse(null)
    }

    private fun shouldSkip(
        nausysYacht: RestYacht,
        model: Model,
    ): Boolean {
        val vesselType = VesselType.fromNauSysCategoryId(model.externalCategoryId)
        if (VesselType.shouldSkipVesselType(vesselType)) {
            log.info("Skipping NauSYS yacht ${nausysYacht.id} with vessel type $vesselType")
            return true
        }
        return false
    }

    private fun createTranslations(
        newYacht: Yacht,
        text: RestInternationalText,
        type: TranslationType,
    ) {
        val yachtTranslations = newYacht.yachtTranslations

        languageRepository.findAll().forEach { lang ->
            val existing = yachtTranslations.firstOrNull { existing -> existing.language!!.locale == lang.locale }
            val yachtTranslation =
                existing ?: YachtTranslation().apply {
                    this.yacht = newYacht
                    this.language = lang
                    this.type = type
                }

            val default = text.textEN ?: "No description"
            val lang = LanguageEnum.fromLocale(lang.locale!!)
            // Fall back to the EN default for any locale NauSys doesn't ship a
            // translation for (PL/NL added in V1_68 are the current cases) —
            // YachtTranslation.value is @NotNull, so a null here used to crash
            // the entire agency-level sync transaction with
            // ConstraintViolationException, dropping every yacht in that
            // agency. EN-as-fallback keeps the row valid; downstream UI still
            // prefers the user's locale where one exists.
            val value =
                when (lang) {
                    LanguageEnum.EN -> default
                    LanguageEnum.FR -> text.textFR ?: default
                    LanguageEnum.DE -> text.textDE ?: default
                    LanguageEnum.PT -> default // NauSys ships no PT
                    LanguageEnum.IT -> text.textIT ?: default
                    LanguageEnum.ES -> text.textES ?: default
                    LanguageEnum.HR -> text.textHR ?: default
                    else -> default
                }
            yachtTranslation.value = value

            newYacht.yachtTranslations.add(yachtTranslation)
            yachtTranslationRepository.save(yachtTranslation)
        }
    }

    private fun calcEnginePower(
        enginePower: Short?,
        engineNumber: Short?,
    ): Short? {
        val enginePower = enginePower ?: 0
        val engineNumber = engineNumber ?: 1
        val calculatedPower = (enginePower * engineNumber)
        return if (calculatedPower != 0) calculatedPower.toShort() else null
    }
}
