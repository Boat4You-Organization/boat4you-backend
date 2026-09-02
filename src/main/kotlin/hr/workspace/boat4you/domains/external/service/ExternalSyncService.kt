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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

// Deliberately NOT @Transactional anywhere in this class: both warm paths
// below wait on partner HTTP calls, and an ambient transaction pins a Hikari
// connection for that whole wait — Postgres kills it after
// idle_in_transaction_session_timeout (180 s: role boat4you_app setting on
// cusma4, not the server default) and the pool ran 6/25 short at all times,
// starving the booking flow ("Connection is not available" bursts).
// Repository reads open their own short transactions; sync services manage
// their own write transactions (TransactionTemplate / REQUIRES_NEW); the
// per-yacht path resolves its lazy entity chain in an explicit short
// read-only TransactionTemplate BEFORE the partner call (see resolveTarget).
@Service
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
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ExternalSyncService::class.java)

    // Short read-only tx for the yacht -> agency -> primarySource lazy chain
    // (open-in-view=false); closed before the partner call starts.
    private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

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

            // In-thread, sequential partner calls — never fan out to taskExecutor
            // from a task already running on it. The old dispatch-and-join(5 min)
            // queued the inner NauSys/MMK tasks behind other outer tasks on the
            // same 6-thread pool (or the F1-064 handler dropped them, leaving
            // futures that never complete), so every saturated warm blocked the
            // full 5 minutes and the cache marker below was never written —
            // the same ranges re-warmed forever. Partner clients carry their own
            // read timeouts + retry caps, so each call is bounded.
            nauSysYachtOfferIntegrationServiceAsync.syncOffersForDateRangeBlocking(
                startDate,
                endDate,
                nausysGroup.countries,
                nausysGroup.regions,
                nausysGroup.locations,
            )
            mmkYachtOfferIntegrationServiceAsync.syncOffersForDateRangeBlocking(
                startDate,
                endDate,
                mmkGroup.countryCodes,
                mmkGroup.regions,
                mmkGroup.locations,
            )
            serviceCallCacheService.saveYachtSearch(startDate, endDate, locations)
        } catch (e: Exception) {
            log.error("Failed to sync yacht offers for locations {}: {}", locations, e.message, e)
        }
    }

    // Tx-free like the location-path warm above. Until 2.9.2026 this carried
    // @Transactional(readOnly = true) for the lazy entity chain, which kept
    // the Hikari connection "idle in transaction" for the whole advisory-locked
    // partner call; the 180 s role kill hit it nightly around 01:00 when MMK
    // was slow (the only confirmed prod idle-in-tx source). The chain is now
    // resolved in resolveTarget's short read tx, so during the HTTP wait the
    // only held connection is the advisory-lock session (autocommit, `idle`),
    // which Postgres never kills; at most taskExecutor.maxPoolSize (6) of them.
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
        val target =
            try {
                resolveTarget(yachtId)
            } catch (e: Exception) {
                log.error("Failed to sync offers for yacht {}: {}", yachtId, e.message, e)
                null
            } ?: return

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
                when (target.externalSystemId) {
                    ExternalSystemEnum.NAUSYS.value -> {
                        nauSysYachtOfferIntegrationService.syncOffersForYachtIdAndDateRage(
                            target.externalYachtId,
                            startDate,
                            endDate,
                        )
                    }

                    ExternalSystemEnum.MMK.value -> {
                        mmkYachtOfferIntegrationService.syncOffersForYachtIdAndDateRage(
                            target.externalYachtId,
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

    private data class PerYachtTarget(
        val externalSystemId: Int,
        val externalYachtId: Long,
    )

    // DB phase of the per-yacht warm: everything that touches lazy
    // associations runs here, inside one short read-only transaction, and
    // only plain ids leave it. Returns null on every "skip" branch.
    private fun resolveTarget(yachtId: Long): PerYachtTarget? =
        readTx.execute {
            val yacht = yachtRepository.findById(yachtId).orElseThrow { YachtDoesNotExistException() }
            if (yacht.entryType != EntryType.EXTERNAL) {
                return@execute null
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
                return@execute null
            }
            val externalSystemId = externalSystem.id ?: return@execute null
            val resolvedYachtId = yacht.id ?: return@execute null
            val yachtMapping =
                externalMappingService.findBySystemIdAndExternalSystemAndType(
                    resolvedYachtId,
                    externalSystem,
                    Yacht::class.simpleName.toString(),
                )
            val externalYachtId = yachtMapping?.externalId ?: return@execute null
            PerYachtTarget(externalSystemId, externalYachtId)
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
            countryCodes = groupedLocations?.get(LocationType.COUNTRY)?.mapNotNull { it.countryCode },
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
