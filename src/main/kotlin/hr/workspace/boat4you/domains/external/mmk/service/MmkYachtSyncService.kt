package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.common.services.extractAndMultiplyNumbers
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
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
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LanguageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
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
import hr.workspace.boat4you.domains.catalouge.services.ModelQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import hr.workspace.boat4you.domains.external.utils.Matchers
import org.openapitools.client.mmk.model.Description
import org.openapitools.client.mmk.model.Image
import org.openapitools.client.mmk.model.Product
import org.openapitools.client.mmk.model.YachtEquipmentInner
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.jvm.optionals.getOrNull

@Service
class MmkYachtSyncService(
    private val yachtRepository: YachtRepository,
    private val externalMappingRepository: ExternalMappingRepository,
    private val externalSystemService: ExternalSystemService,
    private val modelRepository: ModelRepository,
    private val externalMappingService: ExternalMappingService,
    private val yachtImageRepository: YachtImageRepository,
    private val manufacturerRepository: ManufacturerRepository,
    private val locationQueryingService: LocationQueryingService,
    private val reservationOptionRepository: ReservationOptionRepository,
    private val yachtEquipmentRepository: YachtEquipmentRepository,
    private val equipmentRepository: EquipmentRepository,
    private val modelQueryingService: ModelQueryingService,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val extraRepository: ExtraRepository,
    private val yachtExtraRepository: YachtExtraRepository,
    private val fileSystemService: FileSystemService,
    private val languageRepository: LanguageRepository,
    private val yachtTranslationRepository: YachtTranslationRepository,
    private val agencyRepository: AgencyRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        val MMK_ALLOWED_PRODUCTS =
            listOf(
                "bareboat",
                "crewed",
                "allinclusive",
                "cruise",
            )
    }

    @Transactional
    fun syncYachtsForAgency(
        agencyId: Long,
        mmkYachts: List<org.openapitools.client.mmk.model.Yacht>,
    ) {
        val agency = agencyRepository.findById(agencyId).get()
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
            )
        val allLocationMappings =
            externalMappingService.getCachedAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        val syncedYachts = mutableSetOf<Long>()

        mmkYachts.forEach { mmkYacht ->
            if (shouldSkip(mmkYacht)) {
                return@forEach
            }

            val mapping = allMappings.find { mapping -> mapping.externalId == mmkYacht.id!! }
            var isNewEntity = false
            val yacht =
                if (mapping != null) {
                    yachtRepository.findById(mapping.systemId!!).get()
                } else {
                    isNewEntity = true
                    Yacht()
                }

            val filteredProducts =
                mmkYacht.products?.filter { MMK_ALLOWED_PRODUCTS.contains(it.name.lowercase()) }!!
            val updatedYacht = updateFromMmkModel(yacht, mmkYacht, agency, allLocationMappings, filteredProducts)
            val sortedImages = mmkYacht.images?.sortedWith(
                compareBy<Image> { it.description?.equals("Main image", ignoreCase = true) != true }
                    .then(compareBy(nullsLast()) { it.sortOrder })
            )
            syncPictures(
                sortedImages,
                isNewEntity,
                updatedYacht
            )
            yacht.mainImageId = getMainImage(updatedYacht)?.id
            syncEquipment(updatedYacht, mmkYacht.equipment?.toSet())
            syncExtras(updatedYacht, filteredProducts)
            syncReservationOptions(updatedYacht, mmkYacht)

            if (mapping == null) {
                externalMappingRepository.save(
                    ExternalMapping(
                        externalId = mmkYacht.id!!,
                        externalSystem = externalSystem,
                        systemId = updatedYacht.id!!,
                        type = Yacht::class.simpleName.toString(),
                        extendedType = YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
                    ),
                )
            }
        }

        log.warn(
            "Deactivating yachts for agency ${agency.id}, yachts not in MMK response: ${
                syncedYachts.joinToString(
                    ", ",
                )
            }",
        )
        val yachtsToDeactivate = yachtRepository.findAllByAgencyAndIdNotIn(agency, syncedYachts.toList())
        yachtsToDeactivate.forEach {
            it.sysActive = false
            yachtRepository.save(it)
        }
    }

    private fun updateFromMmkModel(
        yacht: Yacht,
        mmkYacht: org.openapitools.client.mmk.model.Yacht,
        agency: Agency,
        allLocationMappings: List<ExternalMapping>,
        filteredProducts: List<Product>,
    ): Yacht {
        val locationMapping =
            allLocationMappings.find { location -> location.externalId == mmkYacht.homeBaseId }
        val location = locationQueryingService.getCachedLocationById(locationMapping!!.systemId!!)

        val model = syncAndGetModel(mmkYacht)

        yacht.agency = agency
        yacht.location = location
        yacht.model = model
        yacht.name = mmkYacht.name.ifBlank { mmkYacht.model?.ifBlank { "N/A" } }
        yacht.deposit = mmkYacht.deposit?.toBigDecimal()
        yacht.insuredDeposit = null
        yacht.depositCurrency = mmkYacht.currency
        yacht.buildYear = mmkYacht.year?.toShort()
        yacht.launchYear = null
        yacht.enginePower =
            if (!mmkYacht.engine.isNullOrEmpty()) extractAndMultiplyNumbers(mmkYacht.engine!!)?.toShort() else null
        yacht.draught = mmkYacht.draught?.toBigDecimal()
        yacht.beam = mmkYacht.beam?.toBigDecimal()
        yacht.waterTank = mmkYacht.waterCapacity?.toInt()
        yacht.fuelTank = mmkYacht.fuelCapacity?.toInt()
        yacht.cabins = mmkYacht.cabins?.toShort()
        yacht.crewCabins = null
        yacht.wc = mmkYacht.wc?.toShort()
        yacht.crewWc = null
        yacht.berths = mmkYacht.berths?.toShort()
        yacht.crewBerths = null
        yacht.maxPersons = mmkYacht.maxPeopleOnBoard?.toShort()
        yacht.defaultCheckin = mmkYacht.defaultCheckInTime
        yacht.defaultCheckout = mmkYacht.defaultCheckOutTime
        yacht.mainsailType = SailTypeEnum.fromMmkValue(mmkYacht.mainsailType)
        yacht.mainsailArea = mmkYacht.mainsailArea?.toBigDecimal()
        yacht.genoaType = SailTypeEnum.fromMmkValue(mmkYacht.genoaType)
        yacht.genoaArea = mmkYacht.genoaArea?.toBigDecimal()
        yacht.registrationNumber = mmkYacht.certificate
        yacht.optionApproval = false
        yacht.optionToReservation = false
        yacht.commision = null
        yacht.commisionPerc = mmkYacht.commissionPercentage?.toBigDecimal()
        yacht.maxDiscount = null
        yacht.maxDiscountFromCommision = null
        yacht.agencyDiscountType = null
        yacht.length = mmkYacht.length?.toBigDecimal()
        if (mmkYacht.crew?.isNotEmpty() == true) {
            yacht.crewNumber = mmkYacht.crew!!.size!!.toShort()
        }

        yacht.entryType = EntryType.EXTERNAL
        yacht.extCharterType = filteredProducts.joinToString(",") { it.name!! }
        syncCharterTypes(yacht, filteredProducts)
        yacht.extCrewedType = null
        yacht.vesselType = VesselType.fromMmkYachtType(mmkYacht.kind)

        yacht.sysActive = true
        // we don't update fileds: excludeDiscount

        return yachtRepository.save(yacht)
    }

    private fun syncCharterTypes(
        yacht: Yacht,
        filteredProducts: List<Product>,
    ) {
        val yachtCharterTypes = yacht.yachtCharterTypes
        if (yachtCharterTypes
                .map { it.type }
                .containsAll(filteredProducts.map { CharterType.fromMmkValue(it.name!!) })
        ) {
            return
        }

        yachtCharterTypes.clear()
        filteredProducts.forEach { product ->
            val charterType = CharterType.fromMmkValue(product.name)
            yachtCharterTypes.add(YachtCharterType(yacht = yacht, type = charterType))
        }
    }

    private fun syncAndGetModel(mmkYacht: org.openapitools.client.mmk.model.Yacht): Model? {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        return if (mmkYacht.modelId == null) {
            null
        } else {
            val modelById =
                modelQueryingService.findModelByExternalIdAndExternalSystem(
                    mmkYacht.modelId!!,
                    ExternalSystemEnum.MMK.value.toLong(),
                )

            if (modelById != null) {
                modelById
            } else {
                val mmkModelName = mmkYacht.model ?: "Unknown"
                val modelByName =
                    try {
                        modelQueryingService.findByNameIgnoreCaseAndExternalManufacturerId(
                            mmkModelName,
                            mmkYacht.shipyardId ?: -1L,
                            ExternalSystemEnum.MMK.value,
                        )
                    } catch (e: Exception) {
                        log.error(
                            "Error finding model for mmkModelId: ${mmkYacht.modelId} mmkModelName: $mmkModelName mmkManufacturerId ${mmkYacht.shipyardId}",
                            e,
                        )
                        throw e
                    }
                return if (modelByName != null) {
                    // sync metadata for future use
                    externalMappingService.saveMapping(
                        mmkYacht.modelId!!,
                        modelByName.id!!,
                        externalSystem,
                        Model::class.simpleName.toString(),
                    )
                    modelByName
                } else {
                    syncModel(externalSystem, mmkYacht.modelId!!, mmkModelName, mmkYacht.shipyardId)
                }
            }
        }
    }

    private fun syncModel(
        externalSystem: ExternalSystem,
        mmkModelId: Long,
        mmkModelName: String?,
        mmkShipyardId: Long?,
    ): Model {
        val mmkShipyardMappings =
            externalMappingService.getCachedAllMappingsByType(
                Manufacturer::class.simpleName.toString(),
                externalSystem,
            )
        val mmkShipyardMapping = mmkShipyardMappings.find { it.externalId == mmkShipyardId }
        val manufacturer =
            if (mmkShipyardMapping?.systemId != null) {
                manufacturerRepository.findById(mmkShipyardMapping!!.systemId!!).getOrNull()
            } else {
                null
            }

        val model = Model()
        model.name = mmkModelName ?: "Unknown"
        model.manufacturer = manufacturer

        modelRepository.save(model)

        externalMappingService.saveMapping(
            mmkModelId,
            model.id!!,
            externalSystem,
            Model::class.simpleName.toString(),
        )

        return model
    }

    private fun syncPictures(
        mmkPictures: List<Image>?,
        isNewEntity: Boolean,
        yacht: Yacht,
    ) {
        if (mmkPictures.isNullOrEmpty()) {
            return
        }
        val useSortOrder = mmkPictures.mapNotNull { it.sortOrder }.distinct().size > 1
        if (isNewEntity) {
            mmkPictures.forEachIndexed { index, mmkYachtPicture ->
                createNewYachtImage(
                    yacht,
                    index == 0,
                    mmkYachtPicture,
                    if (useSortOrder) mmkYachtPicture.sortOrder else index
                )
            }
        } else {
            val allYachImages = yacht.yachtImages
            mmkPictures.forEachIndexed { index, mmkYachtPicture ->
                val yachtImage = allYachImages.find { it.externalUrl == mmkYachtPicture.url }
                if (yachtImage == null) {
                    createNewYachtImage(
                        yacht,
                        index == 0,
                        mmkYachtPicture,
                        if (useSortOrder) mmkYachtPicture.sortOrder else index
                    )
                } else {
                    if (useSortOrder && mmkYachtPicture.sortOrder != null) {
                        yachtImage.position = mmkYachtPicture.sortOrder.toShort()
                        yachtImageRepository.save(yachtImage)
                    }
                    // update previously saved without flag main image
                    if (mmkYachtPicture.description.equals("Main image", ignoreCase = true) || index != 0) {
                        yachtImage.mainImage = true
                        yachtImageRepository.save(yachtImage)
                    }
                    // remove older incorrectly saved
                    if (!mmkYachtPicture.description.equals("Main image", ignoreCase = true) || index != 0) {
                        yachtImage.mainImage = false
                        yachtImageRepository.save(yachtImage)
                    }
                }
            }
            // delete images that are not in the list
            val toRemove = mutableListOf<YachtImage>()
            allYachImages.forEach { yachtImage ->
                if (mmkPictures.none { it.url == yachtImage.externalUrl }) {
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
        first: Boolean,
        mmkImage: Image,
        position: Int?,
    ) {
        try {
            if (mmkImage.url.isNullOrBlank()) {
                return
            }

            val newYachtImage = YachtImage()
            newYachtImage.yacht = yacht
            newYachtImage.url = null
            newYachtImage.externalUrl = mmkImage.url
            newYachtImage.position = position?.toShort()
            newYachtImage.mainImage = mmkImage.description.equals("Main image", ignoreCase = true) || first
            newYachtImage.synced = false
            yacht.yachtImages.add(newYachtImage)
            yachtImageRepository.save(newYachtImage)
        } catch (e: Exception) {
            log.error("Failed to create yacht image for yacht ${yacht.id} ${e.message}")
        }
    }

    private fun getMainImage(yacht: Yacht): YachtImage? {
        return yacht.yachtImages.firstOrNull { it.mainImage == true } ?: yacht.yachtImages.firstOrNull()
    }

    private fun syncEquipment(
        yacht: Yacht,
        mmkYachtEquipment: Set<YachtEquipmentInner>?,
    ) {
        if (mmkYachtEquipment.isNullOrEmpty()) {
            return
        }

        val allYachtEquipment = yacht.yachtEquipments
        val allEquipment = equipmentRepository.findAll()
        val allExternalEquipment = externalEquipmentRepository.getCachedByExternalSystemId(ExternalSystemEnum.MMK.value)
        val matchedIds = mutableSetOf<Long>()

        mmkYachtEquipment.forEach { mmkEquipment ->
            // for each mmk equipment, find the external MMK equipment and try to match it with our equipment
            // we are using the name of the external equipment because name can be empty in /yacht endpoint response
            val externalEquipmentMatch =
                allExternalEquipment.firstOrNull { eq -> eq.externalId == mmkEquipment.id!!.toLong() }
            if (externalEquipmentMatch == null) {
                log.trace("MMK equipment not found in MMK external equipment: {}", mmkEquipment)
                return@forEach
            }
            val boat4youEquipmentMatch =
                allEquipment.firstOrNull { eq ->
                    Matchers.extrasNameMatch(eq.getMatchKeysList(), externalEquipmentMatch.name)
                }

            val equipmentAlreadyOnYacht =
                allYachtEquipment.find { eq ->
                    eq.equipment?.id == boat4youEquipmentMatch?.id || eq.name == externalEquipmentMatch.name
                }

            if (equipmentAlreadyOnYacht != null) {
                // change equipment match
                if (equipmentAlreadyOnYacht.equipment == null && boat4youEquipmentMatch != null) {
                    equipmentAlreadyOnYacht.equipment = boat4youEquipmentMatch
                    yachtEquipmentRepository.save(equipmentAlreadyOnYacht)
                }
                matchedIds.add(equipmentAlreadyOnYacht.id!!)
                return@forEach
            }

            val yachtEquipment = YachtEquipment()
            yachtEquipment.equipment = boat4youEquipmentMatch
            yachtEquipment.name = externalEquipmentMatch.name
            yachtEquipment.externalId = externalEquipmentMatch.externalId!!
            yachtEquipment.yacht = yacht
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
        filteredProducts: List<Product>,
    ) {
        val allExtras = extraRepository.findAll()
        val allYachtExtras = yacht.yachtExtras
        val matchedIds = mutableSetOf<Long>()

        filteredProducts.forEach { product ->
            product.extras.forEach { mmkExtra ->
                val boat4youExtrasMatch =
                    allExtras.firstOrNull { eq ->
                        Matchers.extrasNameMatch(eq.getMatchKeysList(), mmkExtra.name)
                    }

                val extraAlreadyOnYacht =
                    allYachtExtras.find { ex ->
                        ex.externalId == mmkExtra.id
                    }

                if (extraAlreadyOnYacht != null) {
                    extraAlreadyOnYacht.extras = boat4youExtrasMatch
                    extraAlreadyOnYacht.price = mmkExtra.price?.toBigDecimal()
                    extraAlreadyOnYacht.payableInBase = mmkExtra.payableInBase
                    extraAlreadyOnYacht.unit = ExtrasUnitType.fromMmkValue(mmkExtra.unit)
                    extraAlreadyOnYacht.obligatory = mmkExtra.obligatory
                    extraAlreadyOnYacht.externalUnit = mmkExtra.unit
                    extraAlreadyOnYacht.validFrom = mmkExtra.validDateFrom?.value?.toLocalDate()
                    extraAlreadyOnYacht.validTo = mmkExtra.validDateTo?.value?.toLocalDate()
                    matchedIds.add(extraAlreadyOnYacht.id!!)
                    return@forEach
                }

                val yachtExtra = YachtExtra()
                yachtExtra.extras = boat4youExtrasMatch
                yachtExtra.name = mmkExtra.name
                yachtExtra.price = mmkExtra.price?.toBigDecimal()
                yachtExtra.payableInBase = mmkExtra.payableInBase
                yachtExtra.unit = ExtrasUnitType.fromMmkValue(mmkExtra.unit)
                yachtExtra.obligatory = mmkExtra.obligatory
                yachtExtra.externalId = mmkExtra.id
                yachtExtra.yacht = yacht
                yachtExtra.externalUnit = mmkExtra.unit
                yachtExtra.validFrom = mmkExtra.validDateFrom?.value?.toLocalDate()
                yachtExtra.validTo = mmkExtra.validDateTo?.value?.toLocalDate()
                yachtExtra.type = ExtrasType.EXTRAS
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

    private fun syncReservationOptions(
        yacht: Yacht,
        mmkYacht: org.openapitools.client.mmk.model.Yacht,
    ) {
        // MMK doesn't have reservation options so we just set the default one
        val existing = reservationOptionRepository.findOneByYacht(yacht)
        val reservationOption = existing ?: ReservationOption()

        if (existing == null) {
            reservationOption.yacht = yacht
            reservationOption.dateFrom = LocalDate.of(1970, 1, 1)
            reservationOption.dateTo = LocalDate.of(2099, 12, 31)
        }

        reservationOption.minimalDuration = mmkYacht.minimumCharterDuration?.toShort() ?: 7

        if (mmkYacht.allCheckInDays != null) {
            val checkinDays = mmkYacht.allCheckInDays!!
            reservationOption.checkinMon = checkinDays.contains(2)
            reservationOption.checkinTue = checkinDays.contains(3)
            reservationOption.checkinWed = checkinDays.contains(4)
            reservationOption.checkinThu = checkinDays.contains(5)
            reservationOption.checkinFri = checkinDays.contains(6)
            reservationOption.checkinSat = checkinDays.contains(7)
            reservationOption.checkinSun = checkinDays.contains(1)

            reservationOption.checkoutMon = checkinDays.contains(2)
            reservationOption.checkoutTue = checkinDays.contains(3)
            reservationOption.checkoutWed = checkinDays.contains(4)
            reservationOption.checkoutThu = checkinDays.contains(5)
            reservationOption.checkoutFri = checkinDays.contains(6)
            reservationOption.checkoutSat = checkinDays.contains(7)
            reservationOption.checkoutSun = checkinDays.contains(1)
        } else {
            // Some kind of fallback, not sure if this might happen. Api is not clear
            val allTrue = mmkYacht.defaultCheckInDay == -1
            reservationOption.checkinMon = allTrue || mmkYacht.defaultCheckInDay == 2
            reservationOption.checkinTue = allTrue || mmkYacht.defaultCheckInDay == 3
            reservationOption.checkinWed = allTrue || mmkYacht.defaultCheckInDay == 4
            reservationOption.checkinThu = allTrue || mmkYacht.defaultCheckInDay == 5
            reservationOption.checkinFri = allTrue || mmkYacht.defaultCheckInDay == 3
            reservationOption.checkinSat = allTrue || mmkYacht.defaultCheckInDay == 7
            reservationOption.checkinSun = allTrue || mmkYacht.defaultCheckInDay == 1
            reservationOption.checkoutMon = allTrue || mmkYacht.defaultCheckInDay == 2
            reservationOption.checkoutTue = allTrue || mmkYacht.defaultCheckInDay == 3
            reservationOption.checkoutWed = allTrue || mmkYacht.defaultCheckInDay == 4
            reservationOption.checkoutThu = allTrue || mmkYacht.defaultCheckInDay == 5
            reservationOption.checkoutFri = allTrue || mmkYacht.defaultCheckInDay == 6
            reservationOption.checkoutSat = allTrue || mmkYacht.defaultCheckInDay == 7
            reservationOption.checkoutSun = allTrue || mmkYacht.defaultCheckInDay == 1
        }

        reservationOptionRepository.save(reservationOption)
    }

    private fun shouldSkip(mmkYacht: org.openapitools.client.mmk.model.Yacht): Boolean {
        val filteredProducts =
            mmkYacht.products?.filter { MMK_ALLOWED_PRODUCTS.contains(it.name.lowercase()) }
        if (filteredProducts.isNullOrEmpty()) {
            log.info("Skipping MMK yacht ${mmkYacht.id} due to no valid products")
            return true
        }

        val vesselType = VesselType.fromMmkYachtType(mmkYacht.kind)
        if (VesselType.shouldSkipVesselType(vesselType)) {
            log.info("Skipping MMK yacht ${mmkYacht.id} with vessel type $vesselType mmkType ${mmkYacht.kind}")
            return true
        }
        return false
    }

    @Transactional
    fun syncYachtsTranslationsForAgency(
        agencyId: Long,
        mmkYachts: List<org.openapitools.client.mmk.model.Yacht>,
        language: LanguageEnum,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agencyId,
            )
        val allAgencyYachts = yachtRepository.findAllByAgencyId(agencyId)

        mmkYachts.forEach { mmkYacht ->
            val mapping = allMappings.find { mapping -> mapping.externalId == mmkYacht.id!! } ?: return@forEach
            val yacht = allAgencyYachts.find { it.id == mapping!!.systemId!! }
            if (yacht == null) {
                log.warn("Yacht with id ${mapping.systemId} not found for MMK yacht ${mmkYacht.id}")
                return@forEach
            }

            mmkYacht.descriptions?.let { descriptions ->
                if (descriptions.isEmpty()) {
                    return@let
                }
                createTranslations(yacht, mmkYacht.descriptions!!, TranslationType.DESCRIPTION, language)
            }
        }
    }

    private fun createTranslations(
        newYacht: Yacht,
        descriptions: List<Description>,
        type: TranslationType,
        language: LanguageEnum,
    ) {
        val yachtTranslations = newYacht.yachtTranslations
        val lang = languageRepository.findAll().first { it.locale == language.locale }

        val existing = yachtTranslations.firstOrNull { existing -> existing.language!!.locale == lang.locale }
        val yachtTranslation =
            existing ?: YachtTranslation().apply {
                this.yacht = newYacht
                this.language = lang
                this.type = type
            }

        yachtTranslation.value =
            descriptions.firstOrNull { it.category == "general" }?.text
        newYacht.yachtTranslations.add(yachtTranslation)
        yachtTranslationRepository.save(yachtTranslation)
    }
}
