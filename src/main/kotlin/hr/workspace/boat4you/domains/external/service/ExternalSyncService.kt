package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalLocationDto
import hr.workspace.boat4you.domains.catalouge.jpa.LocationViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationServiceAsync
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

@Service
@Transactional(readOnly = true)
class ExternalSyncService(
    private val nauSysYachtOfferIntegrationServiceAsync: NauSysYachtOfferIntegrationServiceAsync,
    private val mmkYachtOfferIntegrationServiceAsync: MmkYachtOfferIntegrationServiceAsync,
    private val locationViewRepository: LocationViewRepository,
    private val yachtRepository: YachtRepository,
    private val nauSysYachtOfferIntegrationService: NauSysYachtOfferIntegrationService,
    private val mmkYachtOfferIntegrationService: MmkYachtOfferIntegrationService,
    private val externalMappingService: ExternalMappingService,
    private val serviceCallCacheService: ServiceCallCacheService,
) {
    private val log = LoggerFactory.getLogger(ExternalSyncService::class.java)
    private val yachtSyncsInProgress: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Async("taskExecutor")
    fun syncYachtOffers(
        startDate: LocalDate,
        endDate: LocalDate,
        locations: List<String>,
    ) {
        val shouldCall = serviceCallCacheService.shouldCallYachtSearch(startDate, endDate, locations)
        if (!shouldCall) {
            return
        }
        try {
            val locationGroups = getLocationGroupsByExternalSystem(locations)
            val mmkGroup = locationGroups[ExternalSystemEnum.MMK.value]!!
            val nausysGroup = locationGroups[ExternalSystemEnum.NAUSYS.value]!!

            val nausysFuture =
                nauSysYachtOfferIntegrationServiceAsync.syncOffersForDateRange(
                    startDate,
                    endDate,
                    nausysGroup.countries,
                    nausysGroup.regions,
                    nausysGroup.locations,
                )
            val mmkFuture =
                mmkYachtOfferIntegrationServiceAsync.syncOffersForDateRange(
                    startDate,
                    endDate,
                    mmkGroup.countryCodes,
                    mmkGroup.regions,
                    mmkGroup.locations,
                )

            // 5-minute hard cap. If either partner API hangs (no socket
            // timeout fired), we stop waiting and let the caller move on.
            // Cache marker is only written on clean completion so a half-
            // synced range will be retried by the next user search.
            CompletableFuture.allOf(nausysFuture, mmkFuture)
                .orTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                .join()
            serviceCallCacheService.saveYachtSearch(startDate, endDate, locations)
        } catch (e: Exception) {
            log.error("Failed to sync yacht offers for locations {}: {}", locations, e.message, e)
        }
    }

    @Async("taskExecutor")
    fun syncYachtOffers(
        yachtId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
        val shouldCall = serviceCallCacheService.shouldCallOffer(yachtId, startDate, endDate)
        if (!shouldCall) {
            return
        }

        if (!yachtSyncsInProgress.add(yachtId)) {
            return
        }
        try {
            val yacht = yachtRepository.findById(yachtId).orElseThrow { YachtDoesNotExistException() }
            if (yacht.entryType != EntryType.EXTERNAL) {
                return
            }
            val externalSystem = yacht.agency!!.primarySource!!.externalSystem!!
            val yachtMapping =
                externalMappingService.findBySystemIdAndExternalSystemAndType(
                    yacht.id!!,
                    externalSystem,
                    Yacht::class.simpleName.toString(),
                )!!
            if (yachtMapping.externalId == null) {
                return
            }

            when (externalSystem.id) {
                ExternalSystemEnum.NAUSYS.value -> {
                    nauSysYachtOfferIntegrationService.syncOffersForYachtIdAndDateRage(
                        yachtMapping.externalId!!,
                        startDate,
                        endDate,
                    )
                }

                ExternalSystemEnum.MMK.value -> {
                    mmkYachtOfferIntegrationService.syncOffersForYachtIdAndDateRage(
                        yachtMapping.externalId!!,
                        startDate,
                        endDate,
                    )
                }

                else -> {
                    return
                }
            }

            serviceCallCacheService.saveOfferSync(yachtId, startDate, endDate)
        } catch (e: Exception) {
            log.error("Failed to sync offers for yacht {}: {}", yachtId, e.message, e)
        } finally {
            yachtSyncsInProgress.remove(yachtId)
        }
    }

    private fun getLocationGroupsByExternalSystem(locations: List<String>): Map<Int, LocationExternalGroup> {
        val allExternalLocations =
            locations.flatMap { locationId ->
                val locationType =
                    when (locationId.first()) {
                        'r' -> LocationType.REGION.value
                        'c' -> LocationType.COUNTRY.value
                        'l' -> LocationType.MARINA.value
                        else -> throw IllegalArgumentException("Invalid locationId format: $locationId")
                    }
                locationViewRepository.findExternalIdById(locationId, locationType)
            }

        val mappedLocations = allExternalLocations.groupBy { it.externalSystemId }
        val mmkLocations = mappedLocations[ExternalSystemEnum.MMK.value]
        val nausysLocations = mappedLocations[ExternalSystemEnum.NAUSYS.value]
        val mmkGroup = getGroupedLocations(mmkLocations)
        val nausysGroup = getGroupedLocations(nausysLocations)

        return mapOf(
            ExternalSystemEnum.MMK.value to mmkGroup,
            ExternalSystemEnum.NAUSYS.value to nausysGroup,
        )
    }

    private fun getGroupedLocations(locations: List<ExternalLocationDto>?): LocationExternalGroup {
        val groupedLocations = locations?.groupBy { it.locationType }

        return LocationExternalGroup(
            countries = groupedLocations?.get(LocationType.COUNTRY)?.map { it.externalId },
            countryCodes = groupedLocations?.get(LocationType.COUNTRY)?.map { it.countryCode },
            regions = groupedLocations?.get(LocationType.REGION)?.map { it.externalId },
            locations = groupedLocations?.get(LocationType.MARINA)?.map { it.externalId },
        )
    }

    data class LocationExternalGroup(
        val countries: List<Long>? = null,
        val countryCodes: List<String>? = null,
        val regions: List<Long>? = null,
        val locations: List<Long>? = null,
    )
}
