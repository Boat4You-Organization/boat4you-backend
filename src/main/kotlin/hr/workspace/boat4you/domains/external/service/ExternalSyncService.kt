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
    private val yachtSyncMutex: YachtSyncMutex,
) {
    private val log = LoggerFactory.getLogger(ExternalSyncService::class.java)

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

        // F3-037: cross-VM serialization via Postgres advisory lock.
        // The earlier JVM-local `MutableSet<Long>` only blocked
        // concurrent calls within this JVM; with VM2 and VM3 both
        // serving public yacht-search requests, the same yachtId could
        // sync twice in parallel and race on the cache-marker write
        // (plus double the partner rate-limit pressure). `withYachtLock`
        // returns null without invoking `block` if another VM already
        // holds the lock — same observable behavior as the old
        // `Set.add == false` early-return, just cross-VM correct.
        yachtSyncMutex.withYachtLock(yachtId) {
            try {
                val yacht = yachtRepository.findById(yachtId).orElseThrow { YachtDoesNotExistException() }
                if (yacht.entryType != EntryType.EXTERNAL) {
                    return@withYachtLock
                }
                // F3-038: replace `yacht.agency!!.primarySource!!.externalSystem!!`
                // chain with an explicit guarded resolve. A missing link
                // in this chain previously NPE-d inside the @Async task
                // and the user-search caller would never know. Logging
                // here + skipping the sync keeps the request thread
                // alive and ops can grep the WARN line if a yacht's
                // agency wiring is in a bad state.
                val externalSystem = yacht.agency?.primarySource?.externalSystem
                if (externalSystem == null) {
                    log.warn(
                        "Skipping per-yacht sync for yachtId={}: missing agency/primarySource/externalSystem chain",
                        yachtId,
                    )
                    return@withYachtLock
                }
                val resolvedYachtId = yacht.id ?: return@withYachtLock
                val yachtMapping =
                    externalMappingService.findBySystemIdAndExternalSystemAndType(
                        resolvedYachtId,
                        externalSystem,
                        Yacht::class.simpleName.toString(),
                    )
                if (yachtMapping?.externalId == null) {
                    return@withYachtLock
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
                        return@withYachtLock
                    }
                }

                serviceCallCacheService.saveOfferSync(yachtId, startDate, endDate)
            } catch (e: Exception) {
                log.error("Failed to sync offers for yacht {}: {}", yachtId, e.message, e)
            }
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
