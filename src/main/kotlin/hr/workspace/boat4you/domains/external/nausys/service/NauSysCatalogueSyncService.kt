package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySource
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySourceId
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySourceRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Category
import hr.workspace.boat4you.domains.catalouge.jpa.CategoryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBase
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSeason
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSeasonRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Region
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import org.openapitools.client.nausys.model.RestCharterBaseList
import org.openapitools.client.nausys.model.RestCharterCompanyList
import org.openapitools.client.nausys.model.RestCountryList
import org.openapitools.client.nausys.model.RestEquipmentList
import org.openapitools.client.nausys.model.RestLocationList
import org.openapitools.client.nausys.model.RestRegionList
import org.openapitools.client.nausys.model.RestSeasonList
import org.openapitools.client.nausys.model.RestServiceList
import org.openapitools.client.nausys.model.RestYachtBuilderList
import org.openapitools.client.nausys.model.RestYachtModelList
import org.openapitools.client.nausys.model.YachtCategoriesResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class NauSysCatalogueSyncService(
    private val externalSystemService: ExternalSystemService,
    private val externalMappingService: ExternalMappingService,
    private val countryRepository: CountryRepository,
    private val regionRepository: RegionRepository,
    private val locationRepository: LocationRepository,
    private val locationQueryingService: LocationQueryingService,
    private val manufacturerRepository: ManufacturerRepository,
    private val modelRepository: ModelRepository,
    private val categoryRepository: CategoryRepository,
    private val agencyRepository: AgencyRepository,
    private val agencySourceRepository: AgencySourceRepository,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val externalSeasonRepository: ExternalSeasonRepository,
    private val externalBaseRepository: ExternalBaseRepository,
    private val yachtRepository: YachtRepository,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * this method should be run only once to sync agencies from nausys
     */
    @Transactional
    fun syncAgenciesByVatCode(nausysAgencies: RestCharterCompanyList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Agency::class.simpleName.toString(), externalSystem)
        val allCountryMappings =
            externalMappingService.getAllMappingsByType(Country::class.simpleName.toString(), externalSystem)
        val allCountries = countryRepository.findAll()

        nausysAgencies.companies?.forEach {
            val mapping = allMappings.find { mapping -> mapping.externalId == it.id }

            val agency =
                if (mapping != null) {
                    agencyRepository.findById(mapping.systemId!!).get()
                } else {
                    var agency =
                        agencyRepository.findByNameAndNotExistsInOtherSystem(it.name!!, ExternalSystemEnum.MMK.value)
                    if (agency == null && it.vatcode != null) {
                        agency = agencyRepository.findByVatCode(it.vatcode!!)
                    }
                    agency
                }

            val resolvedAgency = if (agency == null) {
                // create new agency for unmatched NauSYS company
                val newAgency = Agency()
                newAgency.name = it.name
                newAgency.address = it.address
                newAgency.city = it.city
                newAgency.zip = it.zip
                newAgency.vatCode = it.vatcode
                newAgency.web = it.web
                newAgency.email = it.email
                newAgency.phone = it.phone
                newAgency.mobile = it.mobile
                newAgency.active = true

                val countryMapping = allCountryMappings.find { cm -> cm.externalId == it.countryId }
                val country = if (countryMapping != null) allCountries.find { c -> c.id == countryMapping.systemId?.toInt() } else null
                newAgency.country = country?.name

                agencyRepository.saveAndFlush(newAgency)
                log.info("Created NEW agency ${newAgency.id} (${newAgency.name}) for NauSYS company ${it.id}")
                newAgency
            } else {
                // activate existing agency so it gets picked up by yacht sync
                if (agency.active != true) {
                    agency.active = true
                    agencyRepository.save(agency)
                    log.info("Activated agency ${agency.id} (${agency.name}) for NauSYS sync")
                }
                agency
            }

            val agencySourceId = AgencySourceId()
            agencySourceId.agencyId = resolvedAgency.id
            agencySourceId.externalSystemId = ExternalSystemEnum.NAUSYS.value
            val existingAgencySource = agencySourceRepository.findById(agencySourceId)

            if (existingAgencySource.isEmpty) {
                val agencySource = AgencySource()
                agencySource.id = agencySourceId
                agencySource.primary = true
                agencySource.externalId = it.id
                agencySource.agency = resolvedAgency
                agencySource.externalSystem = externalSystem
                agencySourceRepository.save(agencySource)
            }

            if (mapping == null) {
                externalMappingService.saveMapping(
                    it.id!!.toLong(),
                    resolvedAgency.id!!.toLong(),
                    externalSystem,
                    Agency::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun updateNausysAgencies(nausysAgencies: RestCharterCompanyList) {
        val allNausysAgencies =
            agencyRepository.findAllActiveByPrimarySyncProvider(ExternalSystemEnum.NAUSYS.value.toLong())

        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allCountryMappings =
            externalMappingService.getAllMappingsByType(Country::class.simpleName.toString(), externalSystem)
        val allCountries = countryRepository.findAll()

        nausysAgencies.companies?.forEach { nausysAgency ->
            val agency = allNausysAgencies.find { a -> a.primarySource?.externalId == nausysAgency.id }

            if (agency == null) {
                log.info("Skipping agency ${nausysAgency.name} with ID ${nausysAgency.id} because it is not configured with Nausys")
                return@forEach
            }

            val countryMapping = allCountryMappings.find { a -> a.externalId == nausysAgency.countryId }
            val country = allCountries.find { c -> c.id == countryMapping?.systemId?.toInt() }

            agency.name = nausysAgency.name
            agency.address = nausysAgency.address
            agency.city = nausysAgency.city
            agency.country = country!!.name
            agency.zip = nausysAgency.zip
            agency.vatCode = nausysAgency.vatcode
            agency.web = nausysAgency.web
            agency.email = nausysAgency.email
            agency.mobile = nausysAgency.mobile
            agency.phone = nausysAgency.phone
            if (!nausysAgency.bankAccounts.isNullOrEmpty()) {
                agency.bankAccounts = nausysAgency.bankAccounts!!.joinToString { "," }
            }

            agencyRepository.save(agency)
        }
    }

    @Transactional
    fun countriesSync(nausysCountries: RestCountryList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Country::class.simpleName.toString(), externalSystem)
        val countries = countryRepository.findAll()

        nausysCountries.countries?.forEach { nausysCountry ->
            val country = countries.find { country -> country.code2 == nausysCountry.code2 }

            if (country == null) {
                log.error("Country with code ${nausysCountry.code2} not found")
                return@forEach
            }

            // create mapping just for the record
            val mapping = allMappings.find { mapping -> mapping.externalId == nausysCountry.id }
            if (mapping == null) {
                externalMappingService.saveMapping(
                    nausysCountry.id!!,
                    country.id!!.toLong(),
                    externalSystem,
                    Country::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun regionsSync(nausysRegions: RestRegionList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Region::class.simpleName.toString(), externalSystem)
        val allCountryMappings =
            externalMappingService.getAllMappingsByType(Country::class.simpleName.toString(), externalSystem)
        nausysRegions.regions?.forEach {
            val mapping = allMappings.find { mapping -> mapping.externalId == it.id }

            val region =
                if (mapping != null) {
                    regionRepository.findById(mapping.systemId!!).get()
                } else {
                    val r = regionRepository.findByName(it.name!!.textEN!!)
                    r ?: Region()
                }

            region.name = it.name?.textEN

            val countryMapping = allCountryMappings.find { countryMapping -> countryMapping.externalId == it.countryId }
            val country = countryRepository.findById(countryMapping?.systemId!!).get()

            region.country = country
            region.countryCode = country.code2
            regionRepository.saveAndFlush(region)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    it.id!!.toLong(),
                    region.id!!.toLong(),
                    externalSystem,
                    Region::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun locationsSync(nausysLocations: RestLocationList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        nausysLocations.locations?.forEach {
            val mapping = allMappings.find { mapping -> mapping.externalId == it.id }

            val location =
                if (mapping != null) {
                    locationQueryingService.getLocationById(mapping.systemId!!)!!
                } else {
                    val l = locationQueryingService.getLocationByNameIgnoreCase(it.name!!.textEN!!)
                    l ?: Location()
                }

            location.name = it.name?.textEN
            // Nausys doesn't return city

            val region =
                regionRepository.findByNausysRegionId(it.regionId!!, ExternalSystemEnum.NAUSYS.value.toLong())
            location.regions.add(region!!)
            val country =
                countryRepository.findByNausysRegionId(it.regionId!!, ExternalSystemEnum.NAUSYS.value.toLong())
            location.countryCode = country?.code2
            location.country = country
            locationRepository.saveAndFlush(location)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    it.id!!,
                    location.id!!,
                    externalSystem,
                    Location::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun categoriesSync(nausysCategories: YachtCategoriesResponse) {
        val allCategories = categoryRepository.findAll()
        nausysCategories.categories?.forEach {
            val match = allCategories.find { category -> category.externalId == it.id }
            val category = match ?: Category()

            category.name = it.name?.textEN
            category.externalId = it.id!!
            categoryRepository.saveAndFlush(category)
        }
    }

    @Transactional
    fun manufacturerSync(nausysManufacturers: RestYachtBuilderList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Manufacturer::class.simpleName.toString(), externalSystem)
        val allManufacturers = manufacturerRepository.findAll()
        nausysManufacturers.builders?.forEach {
            val mapping = allMappings.find { mapping -> mapping.externalId == it.id!! }

            val manufacturer =
                if (mapping != null) {
                    allManufacturers.find { manufacturer -> manufacturer.id == mapping!!.systemId!! }!!
                } else {
                    allManufacturers.find { manufacturer -> manufacturer.name!!.lowercase() == it.name!!.lowercase() }
                        ?: Manufacturer().apply { name = it.name }
                }

            manufacturerRepository.saveAndFlush(manufacturer)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    it.id!!,
                    manufacturer.id!!,
                    externalSystem,
                    Manufacturer::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    fun modelsSync(nausysModels: RestYachtModelList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Model::class.simpleName.toString(), externalSystem)
        val allManufacturerMappings =
            externalMappingService.getAllMappingsByType(Manufacturer::class.simpleName.toString(), externalSystem)
        val allManufacturers = manufacturerRepository.findAll()
        val allModels = modelRepository.findAll()
        nausysModels.models?.forEach {
            val mapping = allMappings.find { mapping -> mapping.externalId == it.id!!.toLong() }

            val model =
                if (mapping != null) {
                    allModels.find { model -> model.id == mapping.systemId!! }!!
                } else {
                    // its possible we get duplicate models in a single sync
                    val m =
                        modelRepository.findByNameIgnoreCaseAndExternalManufacturerId(
                            it.name!!,
                            it.yachtBuilderId?.toLong() ?: -1L,
                            ExternalSystemEnum.NAUSYS.value,
                        )
                    m ?: Model().apply { name = it.name }
                }

            val manufacturerMapping =
                allManufacturerMappings.find { manufacturerMapping -> manufacturerMapping.externalId == it.yachtBuilderId?.toLong() }
            val manufacturer =
                allManufacturers.find { manufacturer -> manufacturer.id == manufacturerMapping!!.systemId!! }

            if (model.manufacturer != null && model.manufacturer!!.id != manufacturer!!.id) {
                log.warn("Model ${model.name} has different existing manufacturer ${model.manufacturer!!.name} than new $manufacturer")
            }
            model.manufacturer = manufacturer
            model.externalCategoryId = it.yachtCategoryId
            // Nausys puts length/beam on the model (RestYachtModel), not the
            // per-yacht payload. Copy them here so yacht sync can resolve the
            // spec-card dimensions via the yacht's model.
            it.loa?.let { loa -> model.length = BigDecimal.valueOf(loa.toDouble()) }
            it.beam?.let { beam -> model.beam = BigDecimal.valueOf(beam.toDouble()) }
            modelRepository.saveAndFlush(model)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    it.id!!.toLong(),
                    model.id!!,
                    externalSystem,
                    Model::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun equipmentSync(nausysEquipment: RestEquipmentList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allExternalEquipment =
            externalEquipmentRepository.findByExternalSystemIdAndType(
                externalSystem.id!!,
                ExternalEquipmentType.EQUIPMENT,
            )

        nausysEquipment.equipment?.forEach { nausysEquipment ->
            val externalEquipment =
                allExternalEquipment.find { e -> e.externalId == nausysEquipment.id }
                    ?: ExternalEquipment()

            externalEquipment.name = nausysEquipment.name!!.textEN!!
            externalEquipment.externalId = nausysEquipment.id
            externalEquipment.externalSystem = externalSystem
            externalEquipment.type = ExternalEquipmentType.EQUIPMENT

            externalEquipmentRepository.saveAndFlush(externalEquipment)
        }
    }

    @Transactional
    fun syncServices(nausysEquipment: RestServiceList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allExternalEquipment =
            externalEquipmentRepository.findByExternalSystemIdAndType(
                externalSystem.id!!,
                ExternalEquipmentType.SERVICE,
            )

        nausysEquipment.services?.forEach { nausysEquipment ->
            val externalEquipment =
                allExternalEquipment.find { e -> e.externalId == nausysEquipment.id }
                    ?: ExternalEquipment()

            externalEquipment.name = nausysEquipment.name!!.textEN!!
            externalEquipment.externalId = nausysEquipment.id
            externalEquipment.externalSystem = externalSystem
            externalEquipment.type = ExternalEquipmentType.SERVICE

            externalEquipmentRepository.saveAndFlush(externalEquipment)
        }
    }

    @Transactional
    fun seasonsSync(nausysSeasons: RestSeasonList) {
        val allExternalSeasons = externalSeasonRepository.findAll()

        nausysSeasons.seasons?.forEach { nausysSeason ->
            val externalSeason =
                allExternalSeasons.find { e -> e.externalId == nausysSeason.id }
                    ?: ExternalSeason()

            externalSeason.defaultSeason = nausysSeason.defaultSeason == true
            externalSeason.name = nausysSeason.season
            externalSeason.externalId = nausysSeason.id
            externalSeason.validFrom = nausysSeason.from?.value
            externalSeason.validTo = nausysSeason.to?.value

            externalSeasonRepository.saveAndFlush(externalSeason)
        }
    }

    @Transactional
    fun basesSync(nausysBases: RestCharterBaseList) {
        val allExternalBases = externalBaseRepository.findAll()

        nausysBases.bases?.forEach { nausysBase ->
            val externalBase =
                allExternalBases.find { e -> e.externalId == nausysBase.id }
                    ?: ExternalBase()

            val agency =
                agencyRepository.findByExternalIdAndExternalSystemId(
                    nausysBase.companyId!!,
                    ExternalSystemEnum.NAUSYS.value.toLong(),
                )
            if (agency == null) {
                return@forEach
            }
            val location =
                locationRepository.findByExternalIdAndExternalSystemId(
                    nausysBase.locationId!!,
                    ExternalSystemEnum.NAUSYS.value.toLong(),
                )
            if (location == null) {
                return@forEach
            }

            externalBase.externalId = nausysBase.id
            externalBase.externalSystemId = ExternalSystemEnum.NAUSYS.value.toLong()
            externalBase.extAgencyId = nausysBase.companyId
            externalBase.agency = agency
            externalBase.extLocationId = nausysBase.locationId
            externalBase.location = location
            externalBase.checkinTime = nausysBase.checkInTime
            externalBase.checkoutTime = nausysBase.checkOutTime

            externalBaseRepository.saveAndFlush(externalBase)
        }
    }

    @Transactional
    fun eliminateDuplicateModels() {
        val duplicateModels = modelRepository.findModelsWithDuplicateNames()
        log.info("Found ${duplicateModels.size} models")

        val modelsGroupedByName = duplicateModels.groupBy { it.name!!.lowercase() }
        modelsGroupedByName.forEach { group ->
            val models = group.value
            val modelYachtCounts =
                models.associate { model ->
                    val modelYachtCount = yachtRepository.countYachtsByModelId(model.id!!)
                    model.id!! to modelYachtCount
                }

            val modelToKeepId = modelYachtCounts.maxByOrNull { it.value }!!
            val modelToKeep = models.find { it.id == modelToKeepId.key }!!
            val modelsToRemove = models.filter { it.id != modelToKeepId.key }
            modelsToRemove.forEach { model ->
                log.info("Removing duplicate model: ${model.id} ${model.name}, keeping ${modelToKeep.id}")

                val yachtsToMigrate = yachtRepository.findByModelId(model.id!!)
                yachtsToMigrate.forEach { yacht ->
                    log.info("Reassigning yacht ${yacht.id} from model ${model.id} to model ${modelToKeep.id}")
                    yacht.model = modelToKeep
                    yachtRepository.save(yacht)
                }

                externalMappingService.migrateMappings(model.id!!, modelToKeep.id!!, "Model")

                modelRepository.delete(model)
            }
        }
    }
}
