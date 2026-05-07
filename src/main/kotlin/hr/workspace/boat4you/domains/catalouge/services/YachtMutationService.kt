package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsResponse
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtRequest
import hr.workspace.boat4you.domains.catalouge.dto.IdDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetail
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LanguageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtCharterType
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImageRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslation
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

@Service
@Transactional
class YachtMutationService(
    private val yachtRepository: YachtRepository,
    private val locationRepository: LocationRepository,
    private val customYachtDetailRepository: CustomYachtDetailRepository,
    private val languageRepository: LanguageRepository,
    private val yachtTranslationRepository: YachtTranslationRepository,
    private val modelRepository: ModelRepository,
    private val yachtImageRepository: YachtImageRepository,
    private val fileSystemService: FileSystemService,
    private val yachtQueryingService: YachtQueryingService,
    private val equipmentRepository: EquipmentRepository,
    private val manufacturerRepository: ManufacturerRepository,
    private val countryRepository: CountryRepository,
) {
    @Caching(
        evict = [
            CacheEvict(value = ["usedVesselTypesCache"], allEntries = true),
            CacheEvict(value = ["vesselTypeYachtCountCache"], allEntries = true),
            CacheEvict(value = ["usedCharterTypesCache"], allEntries = true),
            CacheEvict(value = ["yachtExtrasCache"], allEntries = true),
        ],
    )
    fun createYacht(
        customYachtRequest: CustomYachtRequest,
        mainImage: MultipartFile?,
        images: List<MultipartFile>?,
        pdfFile: MultipartFile?,
    ): CustomYachtDetailsResponse {
        val newYacht = createNewYacht(customYachtRequest)
        createCustomYachtDetails(newYacht, customYachtRequest, pdfFile)
        createTranslations(newYacht, customYachtRequest.descriptions ?: emptyMap(), TranslationType.DESCRIPTION)
        mainImage?.let {
            val img = createNewYachtImage(newYacht, mainImage, 0, true)
            newYacht.mainImageId = img.id
        }
        images?.forEachIndexed { index, img ->
            createNewYachtImage(newYacht, img, index + 1, false)
        }

        val savedYacht = yachtRepository.saveAndFlush(newYacht)
        return yachtQueryingService.getCustomYachtDetails(savedYacht.id!!)
    }

    private fun createNewYacht(customYachtRequest: CustomYachtRequest): Yacht {
        val newYacht = Yacht()
        mergeYacht(newYacht, customYachtRequest)

        newYacht.optionApproval = false
        newYacht.optionToReservation = false
        newYacht.commision = null
        newYacht.commisionPerc = null
        newYacht.excludeDiscount = false
        newYacht.maxDiscount = null
        newYacht.maxDiscountFromCommision = null
        newYacht.entryType = EntryType.CUSTOM
        newYacht.yachtCharterTypes.add(YachtCharterType(yacht = newYacht, type = CharterType.ALL_INCLUSIVE))
        newYacht.sysActive = true
        newYacht.deposit = null
        newYacht.insuredDeposit = null
        newYacht.depositCurrency = null
        newYacht.mainsailType = null
        newYacht.mainsailArea = null
        newYacht.genoaType = null
        newYacht.genoaArea = null
        newYacht.registrationNumber = null
        newYacht.crewCabins = null
        newYacht.wc = null
        newYacht.crewWc = null
        newYacht.crewBerths = null
        customYachtRequest.equipmentIds?.let {
            newYacht.yachtEquipments = getEquipment(customYachtRequest.equipmentIds, newYacht)
        }

        return yachtRepository.saveAndFlush(newYacht)
    }

    @Caching(
        evict = [
            CacheEvict(value = ["usedVesselTypesCache"], allEntries = true),
            CacheEvict(value = ["vesselTypeYachtCountCache"], allEntries = true),
            CacheEvict(value = ["usedCharterTypesCache"], allEntries = true),
            CacheEvict(value = ["yachtExtrasCache"], allEntries = true),
        ],
    )
    fun updateYacht(
        id: Long,
        customYachtRequest: CustomYachtRequest,
    ): CustomYachtDetailsResponse {
        val yacht = yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(yacht.id!!)
                ?: throw IllegalArgumentException("Custom yacht details not found for yacht with id ${yacht.id}")

        mergeYacht(yacht, customYachtRequest)
        if (customYachtRequest.equipmentIds != null) {
            val matched = mutableSetOf<Long>()
            customYachtRequest.equipmentIds.forEach { equipmentId ->
                if (yacht.yachtEquipments.none { it.equipmentId == equipmentId }) {
                    val equipment = equipmentRepository.findById(equipmentId).orElseThrow()
                    val yachtEquipment = YachtEquipment()
                    yachtEquipment.equipment = equipment
                    yachtEquipment.yacht = yacht
                    yacht.yachtEquipments.add(yachtEquipment)
                    matched.add(equipmentId)
                }
                if (yacht.yachtEquipments.any { it.equipmentId == equipmentId }) {
                    matched.add(equipmentId)
                }
            }
            // Remove equipment that is not in the request
            yacht.yachtEquipments.removeIf { it.equipment!!.id !in matched }
        } else {
            yacht.yachtEquipments.clear()
        }
        updateCustomYachtDetails(customYachtDetails, customYachtRequest)
        updateTranslations(yacht, customYachtRequest.descriptions, TranslationType.DESCRIPTION)

        val savedYacht = yachtRepository.saveAndFlush(yacht)
        return yachtQueryingService.getCustomYachtDetails(savedYacht.id!!)
    }

    private fun mergeYacht(
        yacht: Yacht,
        customYachtRequest: CustomYachtRequest,
    ): Yacht {
        val model =
            if (customYachtRequest.modelId != null) {
                modelRepository.getReferenceById(customYachtRequest.modelId)
            } else if (customYachtRequest.modelName != null &&
                (customYachtRequest.manufacturerName != null || customYachtRequest.manufacturerId != null)
            ) {
                createManufacturerAndModel(
                    customYachtRequest.manufacturerName,
                    customYachtRequest.manufacturerId,
                    customYachtRequest.modelName,
                )
            } else {
                null
            }

        yacht.name = customYachtRequest.name
        yacht.model = model
        yacht.buildYear = customYachtRequest.buildYear
        yacht.launchYear = customYachtRequest.launchYear
        yacht.enginePower = customYachtRequest.enginePower
        yacht.length = customYachtRequest.length
        yacht.draught = customYachtRequest.draught
        yacht.beam = customYachtRequest.beam
        yacht.waterTank = customYachtRequest.waterTank
        yacht.fuelTank = customYachtRequest.fuelTank
        yacht.cabins = customYachtRequest.cabins
        yacht.berths = customYachtRequest.berths
        yacht.maxPersons = customYachtRequest.maxPersons
        yacht.defaultCheckin = customYachtRequest.defaultCheckin
        yacht.defaultCheckout = customYachtRequest.defaultCheckout
        yacht.vesselType = customYachtRequest.vesselType
        yacht.crewNumber = customYachtRequest.crewNumber

        // Bind the yacht to the marina-tier Location the admin picked. Both
        // branches of yacht_search_view read `location_from` (= yacht.location_id
        // for custom yachts), and the search predicate adds the parent country/
        // region id alongside the expanded marina list — so a yacht pinned to
        // marina id 1234 shows up under that marina, its region, and its
        // country search pages without further work. Earlier this used the
        // countryId, but Country.id and Location.id are independent BIGSERIAL
        // spaces — picking 86 as a country routinely landed the yacht on a
        // random unrelated marina (e.g. Asker Marina in Norway).
        yacht.location = locationRepository.getReferenceById(
            customYachtRequest.locationId.replace("l-", "", true).toLong(),
        )

        return yacht
    }

    private fun createCustomYachtDetails(
        newYacht: Yacht,
        customYachtRequest: CustomYachtRequest,
        pdfFile: MultipartFile?,
    ): CustomYachtDetail {
        val customYachtDetails = CustomYachtDetail()
        if (pdfFile != null) {
            val pdfFile = fileSystemService.savePdfFile(pdfFile, "y-${newYacht.id}")
            customYachtDetails.pdfUrl = pdfFile.getOrNull()
        }
        customYachtDetails.yacht = newYacht
        mergeCustomYachtDetails(customYachtDetails, customYachtRequest)
        return customYachtDetailRepository.save(customYachtDetails)
    }

    private fun updateCustomYachtDetails(
        customYachtDetails: CustomYachtDetail,
        customYachtRequest: CustomYachtRequest,
    ): CustomYachtDetail {
        mergeCustomYachtDetails(customYachtDetails, customYachtRequest)
        return customYachtDetailRepository.save(customYachtDetails)
    }

    private fun mergeCustomYachtDetails(
        customYachtDetails: CustomYachtDetail,
        customYachtRequest: CustomYachtRequest,
    ): CustomYachtDetail {
        customYachtDetails.lowPrice = customYachtRequest.lowPrice
        customYachtDetails.videoUrl = customYachtRequest.videoUrl
        customYachtDetails.countryKey = customYachtRequest.countryId

        customYachtDetails.country =
            countryRepository.getReferenceById(customYachtRequest.countryId.replace("c-", "", true).toLong())
        customYachtDetails.priceDescription = customYachtRequest.priceDescription
        customYachtDetails.amenitiesText = customYachtRequest.amenitiesText
        customYachtDetails.toysText = customYachtRequest.toysText
        customYachtDetails.engineText = customYachtRequest.engineText

        return customYachtDetails
    }

    private fun createTranslations(
        newYacht: Yacht,
        map: Map<String, String>,
        type: TranslationType,
    ) {
        map.forEach { (languageCode, value) ->
            val language =
                languageRepository.findAll().firstOrNull { it.locale == languageCode }
                    ?: throw IllegalArgumentException("Language with code $languageCode not found")
            val translation = YachtTranslation()
            translation.yacht = newYacht
            translation.language = language
            translation.value = value
            translation.type = type
            yachtTranslationRepository.save(translation)
        }
    }

    private fun updateTranslations(
        yacht: Yacht,
        map: Map<String, String>?,
        type: TranslationType,
    ) {
        val currentTranslations = yacht.yachtTranslations.filter { it.type == type }
        map?.forEach { (languageCode, value) ->
            val language =
                languageRepository.findAll().firstOrNull { it.locale == languageCode }
                    ?: throw IllegalArgumentException("Language with code $languageCode not found")
            val translation =
                currentTranslations.firstOrNull { it.language!!.id == language.id } ?: YachtTranslation().apply {
                    this.yacht = yacht
                    this.language = language
                    this.type = type
                }
            translation.value = value
            yachtTranslationRepository.save(translation)
        }
    }

    private fun createNewYachtImage(
        newYacht: Yacht,
        image: MultipartFile,
        index: Int,
        main: Boolean,
    ): YachtImage {
        val result = fileSystemService.saveImage(image, "y-${newYacht.id}")

        val newYachtImage = YachtImage()
        newYachtImage.yacht = newYacht
        newYachtImage.url = result.getOrNull()
        newYachtImage.externalUrl = null
        newYachtImage.position = index.toShort()
        newYachtImage.mainImage = main
        newYacht.yachtImages.add(newYachtImage)
        return yachtImageRepository.saveAndFlush(newYachtImage)
    }

    private fun getEquipment(
        equipmentIds: Set<Long>?,
        yacht: Yacht,
    ): MutableSet<YachtEquipment> {
        val yachtEquipments = mutableSetOf<YachtEquipment>()
        equipmentIds?.forEach { id ->
            val equipment = equipmentRepository.getReferenceById(id)
            val yachtEquipment = YachtEquipment()
            yachtEquipment.equipment = equipment
            yachtEquipment.yacht = yacht
            yachtEquipments.add(yachtEquipment)
        }
        return yachtEquipments
    }

    private fun createManufacturerAndModel(
        manufacturerName: String?,
        manufacturerId: Long?,
        modelName: String,
    ): Model {
        val manufacturer =
            if (manufacturerId != null) {
                manufacturerRepository.getReferenceById(manufacturerId)
            } else {
                val newManufacturer = Manufacturer()
                newManufacturer.name = manufacturerName
                manufacturerRepository.save(newManufacturer)
            }

        val model = Model()
        model.name = modelName
        model.manufacturer = manufacturer
        return modelRepository.saveAndFlush(model)
    }

    fun addMainImageToYacht(
        id: Long,
        mainImage: MultipartFile,
    ): IdDto {
        val yacht = yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        val oldMainImage = yacht.yachtImages.firstOrNull { it.mainImage == true }
        oldMainImage?.let {
            fileSystemService.deleteFile(oldMainImage?.url!!)
            yachtImageRepository.delete(it)
        }

        val newMainImage = createNewYachtImage(yacht, mainImage, 0, true)
        yacht.mainImageId = newMainImage.id!!

        return IdDto(newMainImage.id!!)
    }

    fun addImagesToYacht(
        id: Long,
        images: List<MultipartFile>,
    ): Set<IdDto> {
        val yacht = yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        val ids = mutableSetOf<IdDto>()
        images.forEachIndexed { index, image ->
            val img = createNewYachtImage(yacht, image, index + 1, false)
            ids.add(IdDto(img.id!!))
        }
        return ids
    }

    fun attachPdfFile(
        id: Long,
        pdfFile: MultipartFile,
    ) {
        val yacht = yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(id)
                ?: throw IllegalArgumentException("Custom yacht details not found for yacht with id $id")
        if (customYachtDetails.pdfUrl != null) {
            fileSystemService.deleteFile(customYachtDetails.pdfUrl!!)
        }
        val pdfResult = fileSystemService.savePdfFile(pdfFile, "y-${yacht.id}")
        customYachtDetails.pdfUrl = pdfResult.getOrNull()
        customYachtDetailRepository.save(customYachtDetails)
    }

    fun deletePdfFile(id: Long) {
        yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(id)
                ?: throw IllegalArgumentException("Custom yacht details not found for yacht with id $id")
        fileSystemService.deleteFile(customYachtDetails.pdfUrl!!)
        customYachtDetails.pdfUrl = null
        customYachtDetailRepository.save(customYachtDetails)
    }

    fun deleteYachtImage(
        id: Long,
        imageId: Long,
    ) {
        yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        val image = yachtImageRepository.findById(imageId).orElseThrow { IllegalArgumentException("Image not found") }
        fileSystemService.deleteFile(image.url!!)
        yachtImageRepository.delete(image)
    }

    @Caching(
        evict = [
            CacheEvict(value = ["usedVesselTypesCache"], allEntries = true),
            CacheEvict(value = ["vesselTypeYachtCountCache"], allEntries = true),
            CacheEvict(value = ["usedCharterTypesCache"], allEntries = true),
            CacheEvict(value = ["yachtExtrasCache"], allEntries = true),
        ],
    )
    fun deleteYacht(id: Long) {
        val yacht = yachtRepository.findById(id).orElseThrow { IllegalArgumentException("Yacht not found") }
        yachtImageRepository.deleteAll(yacht.yachtImages)
        customYachtDetailRepository.deleteByYachtId(id)
        yachtTranslationRepository.deleteByYachtId(id)
        yachtRepository.delete(yacht)
    }
}
