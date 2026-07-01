package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.YachtGroupingProvider
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import org.openapitools.client.mmk.model.Flexibility
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.CompletableFuture

@Service
class MmkYachtOfferIntegrationServiceAsync(
    private val yachtRepository: YachtRepository,
    private val externalMappingService: ExternalMappingService,
    private val externalSystemService: ExternalSystemService,
    private val syncConfigurationProperties: SyncConfigurationProperties,
    private val mmkYachtOfferSyncService: MmkYachtOfferSyncService,
    private val transactionTemplate: TransactionTemplate,
    private val mmkOfferIntegrationUtils: MmkOfferIntegrationUtils,
    private val mmkRetryableClient: MmkRetryableClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Async("taskExecutor")
    fun syncOffersForAgencyYachts(
        agency: Agency,
        agencyExternalId: Long,
    ): CompletableFuture<Unit> {
        try {
            val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
            // for each agency, get all yachts. Some agencies have a lot of yachts, but we should be ok with size of this
            val allAgencyYachts = yachtRepository.findWithReservationOptionsByAgency(agency)
            val allAgencyYachtMappings =
                externalMappingService.getCachedAllMappingsByTypeAndExtendedType(
                    Yacht::class.simpleName.toString(),
                    externalSystem,
                    YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
                )

            val reservationOptionsGroups = YachtGroupingProvider.groupYachtsByReservationOptions(allAgencyYachts)
            reservationOptionsGroups.forEach { reservationOptionsGroup ->
                val yachtsInGroup = reservationOptionsGroup.value
                val mmkYachtIds =
                    allAgencyYachtMappings
                        .filter { m -> yachtsInGroup.map { y -> y.id }.contains(m.systemId) }
                        .map { m -> m.externalId!! }
                        .toList()

                // handle just sat-sat checkin-checkouts
                for (i in 0..syncConfigurationProperties.offerMaxYears) {
                    val startDate = LocalDate.now().plusYears(i.toLong()).withDayOfMonth(1)
                    val endDate = startDate.plusYears(1).minusDays(1)
                    val syncStartDate = LocalDateTime.of(startDate, LocalTime.MIN)
                    val syncEndDate = LocalDateTime.of(endDate, LocalTime.MAX)
                    log.trace("Syncing MMK offer for date range: {} - {}", syncStartDate, syncEndDate)

                    val offersResponse =
                        mmkRetryableClient.getOffersForAsync(
                            dateFrom =
                                MmkDateTimeWrapper(
                                    syncStartDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                                ),
                            dateTo =
                                MmkDateTimeWrapper(
                                    syncEndDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                                ),
                            flexibility = Flexibility._6,
                            companyId = listOf(agencyExternalId),
                            yachtId = mmkYachtIds,
                        )

                    if (offersResponse.isNotEmpty()) {
                        try {
                            transactionTemplate.execute<Unit> {
                                mmkYachtOfferSyncService.syncOffersForAgency(
                                    agency.id!!,
                                    offersResponse,
                                    windowFrom = startDate,
                                    windowTo = endDate,
                                )
                            }
                        } catch (e: Exception) {
                            log.error(
                                "Failed to sync offers for agency: {}, date range: {} - {}",
                                agency.name,
                                syncStartDate,
                                syncEndDate,
                                e,
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to sync offers for agency: {}", agency.name, e)
        }

        return CompletableFuture.completedFuture(Unit)
    }

    /**
     * Sync offers for exact date range — fire-and-forget wrapper for callers
     * OUTSIDE taskExecutor (admin MmkSyncController). Tasks already running on
     * taskExecutor (ExternalSyncService cache-warm) must call
     * [syncOffersForDateRangeBlocking] directly: re-dispatching to the same
     * bounded pool starves the inner task behind outer ones.
     */
    @Async("taskExecutor")
    fun syncOffersForDateRange(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        countries: List<String>?,
        regions: List<Long>?,
        marinas: List<Long>?,
    ): CompletableFuture<Unit> {
        syncOffersForDateRangeBlocking(dateFrom, dateTo, countries, regions, marinas)
        return CompletableFuture.completedFuture(Unit)
    }

    fun syncOffersForDateRangeBlocking(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        countries: List<String>?,
        regions: List<Long>?,
        marinas: List<Long>?,
    ) {
        try {
            val startDate = LocalDateTime.of(dateFrom, LocalTime.MIN)
            val endDate = LocalDateTime.of(dateTo, LocalTime.MAX)
            val callStartTime = Instant.now()
            val offersResponse =
                mmkRetryableClient.getOffersForAsync(
                    dateFrom =
                        MmkDateTimeWrapper(
                            startDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                        ),
                    dateTo =
                        MmkDateTimeWrapper(
                            endDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                        ),
                    flexibility = Flexibility._1,
                    country = countries,
                    sailingAreaId = regions,
                    baseFromId = marinas,
                    baseToId = marinas,
                )

            log.info("Syncing offers took ${Instant.now().toEpochMilli() - callStartTime.toEpochMilli()}ms}")

            if (offersResponse.isNotEmpty()) {
                transactionTemplate.execute<Unit> {
                    mmkYachtOfferSyncService.syncOffers(offersResponse)
                }
            }
        } catch (e: Exception) {
            log.error(
                "Failed to sync offers for date range: {} - {}, countries: {}, regions: {}, marinas: {}",
                dateFrom,
                dateTo,
                countries,
                regions,
                marinas,
                e,
            )
        }
    }

    /**
     * @deprecated Use syncOffersForAgencyYachts instead
     * I'm keeping this in an order to preserve logic for old MMK integration. Syncs for less than 7 days should be handler by yacht search or yacht details.
     */
    @Deprecated("Use syncOffersForAgencyYachts instead")
    @Async("taskExecutor")
    fun syncOffersForAgencyYachtsOld(
        agency: Agency,
        agencyExternalId: Long,
    ): CompletableFuture<Unit> {
        try {
            val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
            // for each agency, get all yachts. Some agencies have a lot of yachts, but we should be ok with size of this
            val allAgencyYachts = yachtRepository.findWithReservationOptionsByAgency(agency)
            val allAgencyYachtMappings =
                externalMappingService.getCachedAllMappingsByTypeAndExtendedType(
                    Yacht::class.simpleName.toString(),
                    externalSystem,
                    YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
                )

            val reservationOptionsGroups = YachtGroupingProvider.groupYachtsByReservationOptions(allAgencyYachts)
            reservationOptionsGroups.forEach { reservationOptionsGroup ->
                val yachtsInGroup = reservationOptionsGroup.value
                val mmkYachtIds =
                    allAgencyYachtMappings
                        .filter { m -> yachtsInGroup.map { y -> y.id }.contains(m.systemId) }
                        .map { m -> m.externalId!! }
                        .toList()

                if (reservationOptionsGroup.key.hasNonStandardCheckIn() || reservationOptionsGroup.key.hasNonStandardCheckOut()) {
                    // sync all other than sat-sat checkin-checkouts
                    val syncEndDate =
                        LocalDateTime.now().plusYears(syncConfigurationProperties.offerMaxYears.toLong())
                    val firstDayOfMonths = mmkOfferIntegrationUtils.getFirstDaysOfMonths(syncEndDate)
                    firstDayOfMonths.forEach { firstDayOfMonth ->
                        val lastDayOfMonth =
                            LocalDateTime.of(YearMonth.from(firstDayOfMonth).atEndOfMonth(), LocalTime.MAX)

                        val offersResponse =
                            mmkRetryableClient.getOffersForAsync(
                                dateFrom =
                                    MmkDateTimeWrapper(
                                        firstDayOfMonth.format(MmkDateTimeWrapper.READ_FORMATTER),
                                    ),
                                dateTo =
                                    MmkDateTimeWrapper(
                                        lastDayOfMonth.format(MmkDateTimeWrapper.READ_FORMATTER),
                                    ),
                                flexibility = Flexibility._5,
                                companyId = listOf(agencyExternalId),
                                yachtId = mmkYachtIds,
                                tripDuration = mmkOfferIntegrationUtils.getTripDurations(reservationOptionsGroup.key.minimalDuration),
                            )
                        if (offersResponse.isNotEmpty()) {
                            transactionTemplate.execute<Unit> {
                                mmkYachtOfferSyncService.syncOffersForAgency(agency.id!!, offersResponse)
                            }
                        }
                    }
                } else if (reservationOptionsGroup.key.hasStandardReservation()) {
                    // handle just sat-sat checkin-checkouts
                    for (i in 0..syncConfigurationProperties.offerMaxYears) {
                        val startDate = LocalDate.now().plusYears(i.toLong()).withDayOfMonth(1)
                        val endDate = startDate.plusYears(1).minusDays(1)
                        val syncStartDate = LocalDateTime.of(startDate, LocalTime.MIN)
                        val syncEndDate = LocalDateTime.of(endDate, LocalTime.MAX)
                        log.trace("Syncing MMK offer for date range: {} - {}", syncStartDate, syncEndDate)

                        val offersResponse =
                            mmkRetryableClient.getOffersForAsync(
                                dateFrom =
                                    MmkDateTimeWrapper(
                                        syncStartDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                                    ),
                                dateTo =
                                    MmkDateTimeWrapper(
                                        syncEndDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                                    ),
                                flexibility = Flexibility._6,
                                companyId = listOf(agencyExternalId),
                                yachtId = mmkYachtIds,
                            )

                        if (offersResponse.isNotEmpty()) {
                            transactionTemplate.execute<Unit> {
                                mmkYachtOfferSyncService.syncOffersForAgency(agency.id!!, offersResponse)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to sync offers for agency: {}", agency.name, e)
        }

        return CompletableFuture.completedFuture(Unit)
    }
}
