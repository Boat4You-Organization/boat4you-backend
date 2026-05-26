package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Region
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.catalouge.services.ManufacturerAliasResolver
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import org.openapitools.client.mmk.model.Base
import org.openapitools.client.mmk.model.Company
import org.openapitools.client.mmk.model.Country
import org.openapitools.client.mmk.model.SailingArea
import org.openapitools.client.mmk.model.Shipyard
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MmkCatalogueSyncService(
    private val externalSystemService: ExternalSystemService,
    private val externalMappingService: ExternalMappingService,
    private val countryRepository: CountryRepository,
    private val agencyRepository: AgencyRepository,
    private val regionRepository: RegionRepository,
    private val manufacturerRepository: ManufacturerRepository,
    private val locationQueryingService: LocationQueryingService,
    private val locationRepository: LocationRepository,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val manufacturerAliasResolver: ManufacturerAliasResolver,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Transactional
    fun updateMmkAgencies(mmkAgencies: List<Company>) {
        val allMmkAgencies = agencyRepository.findAllActiveByPrimarySyncProvider(ExternalSystemEnum.MMK.value.toLong())

        mmkAgencies.forEach { mmkAgency ->
            val agency = allMmkAgencies.find { a -> a.primarySource?.externalId == mmkAgency.id }

            if (agency == null) {
                log.info("Skipping agency ${mmkAgency.name} with ID ${mmkAgency.id} because it is not configured with MMK")
                return@forEach
            }

            agency.name = mmkAgency.name
            agency.address = mmkAgency.address
            agency.city = mmkAgency.city
            agency.country = mmkAgency.country
            agency.zip = mmkAgency.zip
            agency.vatCode = mmkAgency.vatCode
            agency.web = mmkAgency.web
            agency.email = mmkAgency.email
            agency.mobile = mmkAgency.mobile
            agency.phone = mmkAgency.telephone
            agency.bankAccounts = mmkAgency.bankAccountNumber

            agencyRepository.save(agency)
        }
    }

    @Transactional
    fun updateMmkCountries(mmkCountries: List<Country>) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(
                hr.workspace.boat4you.domains.catalouge.jpa.Country::class.simpleName.toString(),
                externalSystem,
            )
        val countries = countryRepository.findAll()

        mmkCountries.forEach { mmkCountry ->
            if (mmkCountry.shortName.isNullOrEmpty() || mmkCountry.longName.isNullOrEmpty()) {
                log.warn("Country short or long code is null or empty for country: ${mmkCountry.name}")
                return@forEach
            }
            val country = countries.find { country -> country.code2 == mmkCountry.shortName }

            if (country == null) {
                log.error("Country with code ${mmkCountry.shortName} not found")
                return@forEach
            }

            val mapping = allMappings.find { mapping -> mapping.externalId == mmkCountry.id }
            if (mapping == null) {
                externalMappingService.saveMapping(
                    mmkCountry.id!!,
                    country.id!!.toLong(),
                    externalSystem,
                    hr.workspace.boat4you.domains.catalouge.jpa.Country::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun updateMmkSailigAreas(mmkSailingAreas: List<SailingArea>) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Region::class.simpleName.toString(), externalSystem)

        mmkSailingAreas.forEach { mmkSailingArea ->
            val mapping = allMappings.find { mapping -> mapping.externalId == mmkSailingArea.id }

            val region =
                if (mapping != null) {
                    regionRepository.findById(mapping.systemId!!).get()
                } else {
                    val r = regionRepository.findByName(mmkSailingArea.name)
                    r ?: Region()
                }

            region.name = mmkSailingArea.name
            regionRepository.saveAndFlush(region)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    mmkSailingArea.id!!,
                    region.id!!.toLong(),
                    externalSystem,
                    Region::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun updateMmkLocations(mmkLocations: List<Base>) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Location::class.simpleName.toString(), externalSystem)
        val allCountryMappings =
            externalMappingService.getAllMappingsByType(
                hr.workspace.boat4you.domains.catalouge.jpa.Country::class.simpleName.toString(),
                externalSystem,
            )
        val allCountries = countryRepository.findAll()

        val allRegionMappings =
            externalMappingService.getAllMappingsByType(
                Region::class.simpleName.toString(),
                externalSystem,
            )
        val allRegions = regionRepository.findAll()

        mmkLocations.forEach { mmkLocation ->
            val mapping = allMappings.find { mapping -> mapping.externalId == mmkLocation.id }

            val location =
                if (mapping != null) {
                    locationQueryingService.getLocationById(mapping.systemId!!)!!
                } else {
                    val l = locationQueryingService.getLocationByNameIgnoreCase(mmkLocation.name)
                    l ?: Location()
                }

            location.name = mmkLocation.name
            location.city = mmkLocation.city

            val regionMappings =
                allRegionMappings
                    .filter { m -> mmkLocation.sailingAreas.contains(m.externalId) }
                    .map { m -> m.systemId!!.toInt() }
            val regions = allRegions.filter { r -> regionMappings.contains(r.id!!) }
            regions.forEach { r ->
                location.regions.add(r)
            }

            val countryMapping = allCountryMappings.find { m -> m.externalId == mmkLocation.countryId }
            val country = allCountries.find { c -> c.id == countryMapping!!.systemId!!.toInt() }
            location.countryCode = country?.code2
            location.country = country
            locationRepository.saveAndFlush(location)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    mmkLocation.id!!.toLong(),
                    location.id!!,
                    externalSystem,
                    Location::class.simpleName.toString(),
                )
            }
        }
    }

    @Transactional
    fun manufacturerSync(mmkManufacturers: List<Shipyard>) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Manufacturer::class.simpleName.toString(), externalSystem)
        val allManufacturers = manufacturerRepository.findAll()
        mmkManufacturers.forEach { mmkManufacturer ->
            val mapping = allMappings.find { mapping -> mapping.externalId == mmkManufacturer.id }

            // Canonicalise partner name first so aliases like "Lagoon-Bénéteau"
            // (MMK) collapse to existing "Lagoon" record instead of forking a
            // duplicate every sync. Mario rule (May 2026): one brand = one row.
            val canonicalName = manufacturerAliasResolver.canonicalName(mmkManufacturer.name)
            val manufacturer =
                if (mapping != null) {
                    allManufacturers.find { manufacturer -> manufacturer.id == mapping!!.systemId!! }!!
                } else {
                    allManufacturers.find { manufacturer -> manufacturer.name!!.lowercase() == canonicalName.lowercase() }
                        ?: Manufacturer().apply { name = canonicalName }
                }

            manufacturerRepository.saveAndFlush(manufacturer)

            if (mapping == null) {
                externalMappingService.saveMapping(
                    mmkManufacturer.id!!,
                    manufacturer.id!!,
                    externalSystem,
                    Manufacturer::class.simpleName.toString(),
                )
            }
        }
    }

    // Evict the externalEquipmentCache so the very next yacht sync rereads
    // the freshly populated/upserted external_equipment rows. Without this
    // eviction the 10-minute cache TTL kept yacht sync calling
    // getCachedByExternalSystemId() with a stale snapshot — and any equipment
    // ID added in this run silently fell into "external equipment not found"
    // until the cache expired.
    @org.springframework.cache.annotation.CacheEvict(value = ["externalEquipmentCache"], allEntries = true)
    @Transactional
    fun equipmentSync(mmkEquipment: List<org.openapitools.client.mmk.model.Equipment>) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val allExternalEquipment = externalEquipmentRepository.findByExternalSystemId(externalSystem.id!!.toInt())

        mmkEquipment.forEach { mmkEquipment ->
            val externalEquipment =
                allExternalEquipment.find { e -> e.externalId == mmkEquipment.id }
                    ?: ExternalEquipment()

            externalEquipment.name = mmkEquipment.name
            externalEquipment.externalId = mmkEquipment.id
            externalEquipment.externalSystem = externalSystem
            externalEquipment.type = ExternalEquipmentType.EQUIPMENT

            externalEquipmentRepository.saveAndFlush(externalEquipment)
        }
    }
}
