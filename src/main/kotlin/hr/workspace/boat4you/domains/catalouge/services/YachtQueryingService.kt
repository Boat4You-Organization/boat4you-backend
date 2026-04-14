package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtDetailsResponse
import hr.workspace.boat4you.domains.catalouge.dto.CustomYachtResponse
import hr.workspace.boat4you.domains.catalouge.dto.VesselTypeYachtCountDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtDetailsDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchParamObject
import hr.workspace.boat4you.domains.catalouge.dto.YachtSearchResponseDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtSearchSelectResult
import hr.workspace.boat4you.domains.catalouge.jpa.YachtSearchView
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.cache.annotation.Cacheable
import org.springframework.core.io.Resource
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class YachtQueryingService(
    private val entityManager: EntityManager,
    private val yachtRepository: YachtRepository,
    private val locationRepository: LocationRepository,
    private val externalReservationRepository: ExternalReservationRepository,
    private val yachtMapper: YachtMapper,
    private val offerRepository: OfferRepository,
    private val customYachtViewRepository: CustomYachtViewRepository,
    private val customYachtDetailRepository: CustomYachtDetailRepository,
    private val yachtTranslationRepository: YachtTranslationRepository,
    private val offerMapper: OfferMapper,
    private val fileSystemService: FileSystemService,
    private val exchangeRateCalculationService: ExchangeRateCalculationService,
    private val yachtExtraRepository: YachtExtraRepository,
    private val externalBaseRepository: ExternalBaseRepository,
) {
    fun getYachts(
        searchParams: YachtSearchParamObject,
        sortBy: String?,
        language: LanguageEnum,
        page: Int,
        size: Int,
    ): PageImpl<YachtSearchResponseDto> {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(YachtSearchSelectResult::class.java)
        val root = cq.from(YachtSearchView::class.java)

        cq.multiselect(
            root.get<Long>("id"),
            root.get<String>("yachtName"),
            root.get<VesselType>("vesselType"),
            root.get<Short>("buildYear"),
            root.get<Short>("maxPersons"),
            root.get<Short>("cabins"),
            root.get<BigDecimal>("length"),
            root.get<String>("modelName"),
            root.get<String>("manufacturerName"),
            root.get<Long>("mainImage"),
            root.get<String>("agencyName"),
            root.get<EntryType>("entryType"),
            cb.countDistinct(root.get<Long>("totalLocations")).alias("sumLocations"),
            cb.least(root.get<CharterType>("charterType")),
            cb.least(root.get<String>("locationFullName")),
            cb.min(root.get<BigDecimal>("clientPrice")),
        )

        val predicates =
            buildYachtSearchPredicates(
                cq,
                cb,
                root,
                searchParams,
            )

        cq.where(*predicates.toTypedArray())

        cq.groupBy(
            root.get<Long>("id"),
            root.get<String>("yachtName"),
            root.get<VesselType>("vesselType"),
            root.get<Short>("buildYear"),
            root.get<Short>("maxPersons"),
            root.get<Short>("cabins"),
            root.get<BigDecimal>("length"),
            root.get<String>("modelName"),
            root.get<String>("manufacturerName"),
            root.get<Long>("mainImage"),
            root.get<String>("agencyName"),
            root.get<EntryType>("entryType"),
        )

        // its easier for FE to send just asc/desc for clientPrice
        when (sortBy) {
            "asc" -> {
                cq.orderBy(cb.asc(cb.min(root.get<BigDecimal>("clientPrice"))))
            }

            "desc" -> {
                cq.orderBy(cb.desc(cb.min(root.get<BigDecimal>("clientPrice"))))
            }

            "lowestPrepayment" -> {
                cq.orderBy(cb.asc(cb.min(root.get<BigDecimal>("lowestPrepayment"))))
            }

            "recommendedScore" -> {
                cq.orderBy(cb.desc(cb.max(root.get<BigDecimal>("recommendedScore"))))
            }

            else -> {
                cq.orderBy(cb.desc(cb.max(root.get<BigDecimal>("recommendedScore"))))
            }
        }

        val query = entityManager.createQuery(cq)
        val pageable = Pageable.ofSize(size).withPage(page)
        query.firstResult = pageable.offset.toInt()
        query.maxResults = pageable.pageSize

        val results = query.resultList

        val yachtOptionIds = findOptionYachts(searchParams.startDate, searchParams.endDate)
        val searchResponseDtos =
            results.map { view ->
                val isOption =
                    if (searchParams.startDate == null || searchParams.endDate == null) {
                        false
                    } else {
                        yachtOptionIds.contains(view.id)
                    }

                yachtMapper.toDto(view, searchParams.currency, language, isOption)
            }

        val total = getYachtSearchTotalCount(searchParams)

        return PageImpl(searchResponseDtos, pageable, total)
    }

    private fun findOptionYachts(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Set<Long> {
        if (startDate == null || endDate == null) return emptySet()

        val externalReservationQuery =
            StringBuilder(
                """
                SELECT er.yacht.id FROM ExternalReservation er
                LEFT JOIN Yacht y ON er.yacht.id = y.id WHERE
                er.dateFrom = :startDate
                AND er.dateTo = :endDate
                """.trimIndent(),
            )

        val externalQuery = entityManager.createQuery(externalReservationQuery.toString(), Long::class.java)
        externalQuery.setParameter("startDate", startDate)
        externalQuery.setParameter("endDate", endDate)
        val externalYachtIds = externalQuery.resultList.toSet()

        return externalYachtIds
    }

    fun getYachtSearchTotalCount(searchParams: YachtSearchParamObject): Long {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(Long::class.java)
        val root = cq.from(YachtSearchView::class.java)

        cq.select(cb.countDistinct(root))

        val predicates =
            buildYachtSearchPredicates(
                cq,
                cb,
                root,
                searchParams,
            )

        cq.where(*predicates.toTypedArray())
        return entityManager.createQuery(cq).singleResult
    }

    private fun buildYachtSearchPredicates(
        cq: CriteriaQuery<*>,
        cb: CriteriaBuilder,
        root: Root<YachtSearchView>,
        searchParams: YachtSearchParamObject,
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        val allMarinas =
            searchParams.locationIds
                ?.flatMap { locationId ->
                    getMarinas(locationId).mapNotNull { it.id }
                }?.distinct()
        if (!allMarinas.isNullOrEmpty()) {
            predicates.add(
                cb.or(
                    root.get<String>("locationFrom").`in`(allMarinas),
                    root.get<String>("locationTo").`in`(allMarinas),
                ),
            )
        }

        if (!searchParams.yachtIds.isNullOrEmpty()) {
            predicates.add(
                root.get<Long>("id").`in`(searchParams.yachtIds),
            )
        }

        if (!searchParams.charterTypes.isNullOrEmpty()) {
            predicates.add(root.get<CharterType>("charterType").`in`(searchParams.charterTypes))
        }

        if (!searchParams.vesselTypes.isNullOrEmpty()) {
            predicates.add(root.get<VesselType>("vesselType").`in`(searchParams.vesselTypes))
        }

        if (!searchParams.manufacturers.isNullOrEmpty()) {
            predicates.add(root.get<Manufacturer>("manufacturerId").`in`(searchParams.manufacturers))
        }

        if (!searchParams.models.isNullOrEmpty()) {
            predicates.add(root.get<Model>("modelId").`in`(searchParams.models))
        }

        if (!searchParams.mainSailTypes.isNullOrEmpty()) {
            predicates.add(root.get<SailTypeEnum>("mainSailType").`in`(searchParams.mainSailTypes))
        }

        if (searchParams.minBuildYear != null && searchParams.maxBuildYear != null) {
            predicates.add(cb.between(root.get("buildYear"), searchParams.minBuildYear, searchParams.maxBuildYear))
        } else if (searchParams.minBuildYear != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("buildYear"), searchParams.minBuildYear))
        } else if (searchParams.maxBuildYear != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("buildYear"), searchParams.maxBuildYear))
        }

        if (searchParams.minPersons != null && searchParams.maxPersons != null) {
            predicates.add(cb.between(root.get("maxPersons"), searchParams.minPersons, searchParams.maxPersons))
        } else if (searchParams.minPersons != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("maxPersons"), searchParams.minPersons))
        } else if (searchParams.maxPersons != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("maxPersons"), searchParams.maxPersons))
        }

        if (searchParams.minCabins != null && searchParams.maxCabins != null) {
            predicates.add(cb.between(root.get("cabins"), searchParams.minCabins, searchParams.maxCabins))
        } else if (searchParams.minCabins != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("cabins"), searchParams.minCabins))
        } else if (searchParams.maxCabins != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("cabins"), searchParams.maxCabins))
        }

        if (searchParams.minBerths != null && searchParams.maxBerths != null) {
            predicates.add(cb.between(root.get("berths"), searchParams.minBerths, searchParams.maxBerths))
        } else if (searchParams.minBerths != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("berths"), searchParams.minBerths))
        } else if (searchParams.maxBerths != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("berths"), searchParams.maxBerths))
        }

        if (searchParams.minLength != null && searchParams.maxLength != null) {
            predicates.add(
                cb.between(
                    root.get("length"),
                    searchParams.getMinLengthInMeters(),
                    searchParams.getMaxLengthInMeters(),
                ),
            )
        } else if (searchParams.minLength != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("length"), searchParams.getMinLengthInMeters()))
        } else if (searchParams.maxLength != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("length"), searchParams.getMaxLengthInMeters()))
        }

        if (searchParams.minPrice != null && searchParams.maxPrice != null) {
            predicates.add(
                cb.between(
                    root.get("clientPrice"),
                    searchParams.getMinPriceInEur(exchangeRateCalculationService),
                    searchParams.getMaxPriceInEur(exchangeRateCalculationService),
                ),
            )
        } else if (searchParams.minPrice != null) {
            predicates.add(
                cb.greaterThanOrEqualTo(
                    root.get("clientPrice"),
                    searchParams.getMinPriceInEur(exchangeRateCalculationService),
                ),
            )
        } else if (searchParams.maxPrice != null) {
            predicates.add(
                cb.lessThanOrEqualTo(
                    root.get("clientPrice"),
                    searchParams.getMaxPriceInEur(exchangeRateCalculationService),
                ),
            )
        }

        if (searchParams.startDate != null && searchParams.endDate != null) {
            if (!searchParams.startDate.isBefore(searchParams.endDate)) {
                throw IllegalArgumentException("Starting date must be before end date")
            }
            predicates.add(cb.greaterThanOrEqualTo(root.get("dateFrom"), searchParams.startDate))
            predicates.add(cb.lessThanOrEqualTo(root.get("dateTo"), searchParams.endDate))
        } else if (searchParams.startDate != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("dateFrom"), searchParams.startDate))
        } else if (searchParams.endDate != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("dateTo"), searchParams.endDate))
        }

        if (searchParams.minWc != null && searchParams.maxWc != null) {
            predicates.add(cb.between(root.get("wc"), searchParams.minWc, searchParams.maxWc))
        } else if (searchParams.minWc != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("wc"), searchParams.minWc))
        } else if (searchParams.maxWc != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("wc"), searchParams.maxWc))
        }

        if (searchParams.minEnginePower != null && searchParams.maxEnginePower != null) {
            predicates.add(
                cb.between(
                    root.get("enginePower"),
                    searchParams.minEnginePower,
                    searchParams.maxEnginePower,
                ),
            )
        } else if (searchParams.minEnginePower != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("enginePower"), searchParams.minEnginePower))
        } else if (searchParams.maxEnginePower != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("enginePower"), searchParams.maxEnginePower))
        }

        if (!searchParams.amenities.isNullOrEmpty()) {
            val countSubquery = cq.subquery(Long::class.java)
            val yachtEquipmentRoot = countSubquery.from(YachtEquipment::class.java)

            countSubquery
                .select(cb.countDistinct(yachtEquipmentRoot.get<Long>("id")))
                .where(
                    cb.and(
                        cb.equal(yachtEquipmentRoot.get<Long>("yachtId"), root.get<Long>("id")),
                        yachtEquipmentRoot.get<Long>("equipmentId").`in`(searchParams.amenities),
                    ),
                )

            predicates.add(
                cb.equal(countSubquery, searchParams.amenities.size.toLong()),
            )
        }

        if (!searchParams.services.isNullOrEmpty()) {
            val countSubquery = cq.subquery(Long::class.java)
            val yachtExtraRoot = countSubquery.from(YachtExtra::class.java)

            countSubquery
                .select(cb.countDistinct(yachtExtraRoot.get<Long>("extrasId")))
                .where(
                    cb.and(
                        cb.equal(yachtExtraRoot.get<Long>("yachtId"), root.get<Long>("id")),
                        yachtExtraRoot.get<Long>("extrasId").`in`(searchParams.services),
                    ),
                )

            predicates.add(
                cb.equal(countSubquery, searchParams.services.size.toLong()),
            )
        }

        return predicates
    }

    private fun getMarinas(locationId: String): List<Location> {
        val locationType =
            when (locationId.first()) {
                'r' -> LocationType.REGION
                'c' -> LocationType.COUNTRY
                'l' -> LocationType.MARINA
                else -> return emptyList()
            }

        val id = locationId.substring(2).toIntOrNull() ?: return emptyList()

        return when (locationType) {
            LocationType.MARINA -> locationRepository.findById(id.toLong()).map { listOf(it) }.orElse(emptyList())
            LocationType.COUNTRY -> locationRepository.findMarinasByCountryId(id)
            LocationType.REGION -> locationRepository.findMarinasByRegionId(id)
        }
    }

    fun getYacht(
        id: Long,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        currency: CurrencyEnum?,
        language: LanguageEnum,
    ): YachtDetailsDto {
        val yacht = getValidYacht(id)

        val offerDto =
            if (dateFrom != null && dateTo != null) {
                val offers = offerRepository.findAllByYachtAndDateFromAndDateTo(yacht, dateFrom!!, dateTo!!)
                offers.map { offerMapper.toDto(it, currency) }
            } else {
                null
            }

        val yachtExtras =
            if (yacht.entryType == EntryType.EXTERNAL) {
                val externalBasesExternalIds =
                    externalBaseRepository
                        .findByAgencyIdAndLocationId(yacht.agency!!.id!!, yacht.location!!.id!!)
                        .map { it.externalId!! }
                val yachtExtraIds =
                    yachtExtraRepository.findYachtExtraIdsGroupedByYacht(
                        yacht.id!!,
                        dateFrom,
                        dateTo,
                        externalBasesExternalIds.toTypedArray(),
                    )
                yachtExtraRepository.findGroupedByYacht(yacht, yachtExtraIds)
            } else {
                emptyList()
            }

        val result =
            yachtMapper.toDetailsDto(
                yacht,
                offerDto,
                yachtExtras,
                currency,
                language,
            )

        return result
    }

    fun getYachtAvailability(
        id: Long,
        month: Int?,
        year: Int,
    ): List<YachtAvailabilityDto> {
        getValidYacht(id) // verify if yacht and agency are active, if not exception is thrown

        val reservations =
            if (month == null) {
                externalReservationRepository.findYachtAvailabilityByYear(id, year)
            } else {
                val startDate = LocalDate.of(year, month, 1)
                val endDate = startDate.withDayOfMonth(startDate.lengthOfMonth())
                externalReservationRepository.findYachtAvailabilityByAdjustedYearAndMonth(id, startDate, endDate)
            }

        val yachtAvailability =
            reservations.map {
                it.toYachtAvailabilityDto()
            }

        return yachtAvailability
    }

    @Cacheable("usedVesselTypesCache")
    fun getUsedVesselTypes(): List<VesselType> {
        return yachtRepository
            .getUsedVesselTypes()
            .map { VesselType.entries[it] }
    }

    @Cacheable("vesselTypeYachtCountCache")
    fun getVesselTypeYachtCount(): List<VesselTypeYachtCountDto> {
        return yachtRepository.getVesselTypeYachtCount().map { row ->
            VesselTypeYachtCountDto(
                vesselType = row[0] as VesselType,
                yachtCount = (row[1] as Number).toInt(),
            )
        }
    }

    @Cacheable("usedCharterTypesCache")
    fun getUsedCharterTypes(): List<CharterType> {
        return yachtRepository
            .getUsedCharterTypes()
            .map { CharterType.entries[it] }
    }

    fun getCustomYachts(
        name: String?,
        pageable: Pageable,
    ): Page<CustomYachtResponse> {
        return if (name.isNullOrBlank()) {
            customYachtViewRepository.findAll(pageable).map { yachtMapper.toDto(it) }
        } else {
            customYachtViewRepository.findAllByNameLikeIgnoreCase(name.trim(), pageable).map { yachtMapper.toDto(it) }
        }
    }

    fun getCustomYachtDetails(id: Long): CustomYachtDetailsResponse {
        val yacht =
            yachtRepository
                .findById(id)
                .orElseThrow { YachtDoesNotExistException() }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(id)
                ?: throw YachtDoesNotExistException()
        val translations = yachtTranslationRepository.findAllByYachtId(id)

        return yachtMapper.toCustomYachtDetailsResponse(yacht, customYachtDetails, translations)
    }

    fun getCustomBoatBrochure(yachtId: Long): Resource {
        yachtRepository
            .findById(yachtId)
            .orElseThrow { YachtDoesNotExistException() }
        val customYachtDetails =
            customYachtDetailRepository.findByYachtId(yachtId)
                ?: throw YachtDoesNotExistException()

        val pdfPath = fileSystemService.getResourcePath(customYachtDetails.pdfUrl!!)

        return fileSystemService.getResourceFromPath(pdfPath)
    }

    private fun getValidYacht(yachtId: Long): Yacht {
        val yacht =
            yachtRepository
                .findById(yachtId)
                .orElseThrow { YachtDoesNotExistException() }

        if (!yacht.sysActive!!) {
            throw YachtNotActiveException()
        }

        val agency = yacht.agency
        if (yacht.entryType == EntryType.EXTERNAL && (agency == null || !agency.active!!)) {
            throw AgencyNotActiveException()
        }

        return yacht
    }
}
