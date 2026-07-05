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
import hr.workspace.boat4you.domains.catalouge.services.ManufacturerAliasResolver
import hr.workspace.boat4you.domains.catalouge.services.applyLocationRegions
import hr.workspace.boat4you.domains.catalouge.services.ModelNameNormaliser
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
    private val manufacturerAliasResolver: ManufacturerAliasResolver,
    private val modelNameNormaliser: ModelNameNormaliser,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Create/match pass of the NauSys agency mirror. Originally a one-time bootstrap; since
     * 5.7.2026 (Mario: the partner's company list IS our agency list) it runs on every
     * scheduled agencies sync so NEW NauSys companies are created automatically. Idempotent:
     * already-mapped companies only get re-activated when the NauSys mirror itself deactivated
     * them (syncDeactivatedBy=NAUSYS) — a manual admin blacklist and the MMK mirror's own
     * deactivations stay untouched. Name/VAT matching NEVER merges into an agency that has an
     * MMK source: yachts only sync through an agency's PRIMARY system, so the same physical
     * company keeps one row per system (Mario decision — e.g. FX Yachting vs Fyly). Field
     * refresh stays in [updateNausysAgencies]; removal lives in AgencyPresenceReconcileService.
     */
    @Transactional
    fun syncAgenciesByVatCode(nausysAgencies: RestCharterCompanyList) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allMappings =
            externalMappingService.getAllMappingsByType(Agency::class.simpleName.toString(), externalSystem)
        val allCountryMappings =
            externalMappingService.getAllMappingsByType(Country::class.simpleName.toString(), externalSystem)
        val allCountries = countryRepository.findAll()
        // A company id duplicated inside one response would create the agency on the first
        // occurrence and then crash the pass on the second (saveMapping unique violation
        // poisons the transaction) — process each id once.
        val processedIds = mutableSetOf<Long>()

        nausysAgencies.companies?.forEach {
            // Partner data is unvetted now that this runs nightly: skip unusable rows and
            // isolate per-company read-path failures (stale mapping, ambiguous match) so one
            // bad company can't abort the whole mirror pass.
            if (it.id == null || it.name.isNullOrBlank()) {
                log.warn("Skipping NauSYS company with missing id/name (id=${it.id})")
                return@forEach
            }
            if (!processedIds.add(it.id!!)) {
                log.warn("NauSYS company ${it.id} (${it.name}) duplicated in response — skipping second occurrence")
                return@forEach
            }
            runCatching {
                val mapping = allMappings.find { mapping -> mapping.externalId == it.id }

                val agency =
                    if (mapping != null) {
                        agencyRepository.findById(mapping.systemId!!).get()
                    } else {
                        // Both matches exclude MMK-sourced agencies (the VAT fallback used to
                        // return a single arbitrary row and crashed on duplicate VATs, which
                        // exist in prod): merging a NauSys company into an MMK-primary agency
                        // would either fork a second "primary" or leave the NauSys fleet
                        // unsynced — one row per system instead.
                        agencyRepository.findByNameAndNotExistsInOtherSystem(it.name!!, ExternalSystemEnum.MMK.value)
                            ?: it.vatcode?.let { vat ->
                                agencyRepository.findAllByVatCode(vat).firstOrNull { candidate ->
                                    candidate.agencySources.none { s -> s.id?.externalSystemId == ExternalSystemEnum.MMK.value }
                                }
                            }
                    }

                val resolvedAgency = if (agency == null) {
                    // create new agency for unmatched NauSYS company
                    val newAgency = Agency()
                    newAgency.name = it.name?.take(255)
                    newAgency.address = it.address?.take(255)
                    newAgency.city = it.city?.take(150)
                    newAgency.zip = it.zip?.take(30)
                    newAgency.vatCode = it.vatcode?.take(100)
                    newAgency.web = it.web?.take(255)
                    newAgency.email = it.email?.take(150)
                    newAgency.phone = it.phone?.take(200)
                    newAgency.mobile = it.mobile?.take(200)
                    newAgency.active = true

                    val countryMapping = allCountryMappings.find { cm -> cm.externalId == it.countryId }
                    val country = if (countryMapping != null) allCountries.find { c -> c.id == countryMapping.systemId?.toInt() } else null
                    newAgency.country = country?.name

                    agencyRepository.saveAndFlush(newAgency)
                    log.info("Created NEW agency ${newAgency.id} (${newAgency.name}) for NauSYS company ${it.id}")
                    newAgency
                } else {
                    // re-activate so it gets picked up by yacht sync — but ONLY if the NauSys
                    // mirror itself deactivated it; an admin's manual blacklist (toggleActive)
                    // and an MMK-mirror deactivation stay off (no cross-system ping-pong)
                    if (agency.active != true && agency.syncDeactivatedBy == ExternalSystemEnum.NAUSYS.value) {
                        agency.active = true
                        agency.syncDeactivatedBy = null
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
                    // primary only when the agency isn't already driven by another system —
                    // two primary sources would make yacht sync + both mirrors fight over one row
                    agencySource.primary = resolvedAgency.agencySources.none { s -> s.primary == true }
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
            }.onFailure { e ->
                log.error("Failed to mirror NauSYS company ${it.id} (${it.name})", e)
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

            // name is @NotNull on the entity — a blank partner row would fail validation on
            // flush and roll back the WHOLE update pass (and kill the rest of the nightly
            // catalogue chain); same reason all fields are truncated to their @Size limits
            if (nausysAgency.name.isNullOrBlank()) {
                log.warn("Skipping field refresh for NauSYS company ${nausysAgency.id}: missing name")
                return@forEach
            }

            val countryMapping = allCountryMappings.find { a -> a.externalId == nausysAgency.countryId }
            val country = allCountries.find { c -> c.id == countryMapping?.systemId?.toInt() }
            if (country == null) {
                // was `country!!` — one company with an unmapped countryId would abort the WHOLE
                // update transaction (and with auto-created companies the exposure grows); keep
                // the previous country value and refresh the rest instead
                log.warn("No country mapping for NauSYS company ${nausysAgency.id} (countryId=${nausysAgency.countryId})")
            }

            agency.name = nausysAgency.name?.take(255)
            agency.address = nausysAgency.address?.take(255)
            agency.city = nausysAgency.city?.take(150)
            country?.let { agency.country = it.name }
            agency.zip = nausysAgency.zip?.take(30)
            agency.vatCode = nausysAgency.vatcode?.take(100)
            agency.web = nausysAgency.web?.take(255)
            agency.email = nausysAgency.email?.take(150)
            agency.mobile = nausysAgency.mobile?.take(200)
            agency.phone = nausysAgency.phone?.take(200)
            if (!nausysAgency.bankAccounts.isNullOrEmpty()) {
                // was `joinToString { "," }` — the lambda MAPPED every account object to a
                // literal comma, storing ",,," instead of the account numbers
                agency.bankAccounts =
                    nausysAgency.bankAccounts!!
                        .mapNotNull { acc -> acc.iban ?: acc.accountNumber }
                        .joinToString(",")
                        .take(255)
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

            // Only set the name from NauSys on first creation. The sync used to overwrite the
            // name on every run, silently reverting manual corrections (e.g.
            // "Marina Frapa, Rogoznica" kept reverting to "Marina Frapa"). Location names
            // rarely change in NauSys, so once set we treat them as manually curatable.
            if (location.name.isNullOrBlank()) {
                location.name = it.name?.textEN
            }
            // Nausys doesn't return city

            val region =
                regionRepository.findByNausysRegionId(it.regionId!!, ExternalSystemEnum.NAUSYS.value.toLong())
            // regions.add() never removes, so a partner mis-classification kept
            // re-appearing after every manual cleanup. applyLocationRegions pins
            // overridden locations (e.g. Marina Frapa Rogoznica -> Split only).
            applyLocationRegions(location, listOfNotNull(region), regionRepository)
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

            // Canonicalise partner-supplied name first — see MmkCatalogueSyncService
            // for full rationale (Lagoon vs Lagoon-Bénéteau dedupe, Mario rule).
            val canonicalName = manufacturerAliasResolver.canonicalName(it.name!!)
            val manufacturer =
                if (mapping != null) {
                    allManufacturers.find { manufacturer -> manufacturer.id == mapping!!.systemId!! }!!
                } else {
                    // trim() too — see MmkCatalogueSyncService (trailing-space
                    // legacy rows would otherwise fork a duplicate manufacturer).
                    allManufacturers.find { manufacturer -> manufacturer.name!!.trim().lowercase() == canonicalName.lowercase() }
                        ?: Manufacturer().apply { name = canonicalName }
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

            // Strip noisy cabin-config suffix ("- 4 + 1 cab.*" etc.) before
            // lookup/create so partner cannot fork "Bali 4.4" into N variants.
            val canonicalModelName = modelNameNormaliser.canonicalName(it.name!!)
            val model =
                if (mapping != null) {
                    allModels.find { model -> model.id == mapping.systemId!! }!!
                } else {
                    val m =
                        modelRepository.findByNameIgnoreCaseAndExternalManufacturerId(
                            canonicalModelName,
                            it.yachtBuilderId?.toLong() ?: -1L,
                            ExternalSystemEnum.NAUSYS.value,
                        )
                    m ?: Model().apply { name = canonicalModelName }
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

    // See MmkCatalogueSyncService.equipmentSync for the rationale —
    // externalEquipmentCache stays around for 10 min, so any yacht sync
    // running in that window would see a stale snapshot of external_equipment.
    // Evicting on each sync ensures yacht-level matchers always read the
    // current set.
    @org.springframework.cache.annotation.CacheEvict(value = ["externalEquipmentCache"], allEntries = true)
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
