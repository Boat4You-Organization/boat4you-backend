package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.test.ProdTestSamples.DREAM_YACHT_AGENCY_ID
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.model.ReservationOptionsGroup
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.IntervalProvider
import hr.workspace.boat4you.domains.external.service.ReservationOptionsCombinationProvider
import hr.workspace.boat4you.domains.external.service.YachtGroupingProvider
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

private const val OFFER_SYNC_HORIZON_MONTHS = 18L

@Service
class NauSysYachtOfferIntegrationService(
    private val agencyRepository: AgencyRepository,
    private val yachtRepository: YachtRepository,
    private val externalMappingService: ExternalMappingService,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val externalSystemService: ExternalSystemService,
    private val nauSysYachtOfferSyncService: NauSysYachtOfferSyncService,
    private val syncConfigurationProperties: SyncConfigurationProperties,
    private val nauSysRetryableClient: NauSysRetryableClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun yachtOfferSync() {
        log.info("Starting NauSYS offer sync")
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())

        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(ExternalSystemEnum.NAUSYS.value.toLong())
        log.info("Doing sync for ${agencies.size} agencies")
        agencies.forEach { agency ->
            try {
                syncOffersForYachts(agency, externalSystem)
            } catch (e: Exception) {
                log.error("Error while syncing Nausys Offers ${agency.id}", e)
            }
        }
        log.info("Finished NauSYS offer sync")
    }

    /***
     * Syncs offers for yachts.
     * Looks for all yachts in a given agency and groups them by their reservation options.
     * For each group, it generates valid combinations of check-in and check-out days,
     * and creates intervals for each combination.
     */
    private fun syncOffersForYachts(
        agency: Agency,
        externalSystem: ExternalSystem,
    ) {
        // for each agency, get all yachts. Some agencies have a lot of yachts, but we should be ok with size of this
        val allAgencyYachts = yachtRepository.findWithReservationOptionsByAgency(agency)
        val allAgencyYachtMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agency.id,
            )
        // get all yachts that have same reservation date options
        val reservationOptionsGroups = YachtGroupingProvider.groupYachtsByReservationOptions(allAgencyYachts)
        reservationOptionsGroups.forEach { reservationOptionsGroup ->
            val yachtsInGroup = reservationOptionsGroup.value
            val nausysYachtIds =
                allAgencyYachtMappings
                    .filter { m -> yachtsInGroup.map { y -> y.id }.contains(m.systemId) }
                    .map { m -> m.externalId!! }
                    .toList()

            val syncEndDate = calcSyncEndDate(reservationOptionsGroup.key)

            log.info(
                "1 - Doing sync for agency ${agency.id} startDate ${reservationOptionsGroup.key.start} syncEndDate $syncEndDate " +
                    "yachtsCount ${nausysYachtIds.size} yachts ${
                        yachtsInGroup.map { y -> y.id }.joinToString { "," }
                    }",
            )

            val reservationIntervals =
                ReservationOptionsCombinationProvider.generateValidCombinations(reservationOptionsGroup.key)
            reservationIntervals.forEach { reservationInterval ->
                log.info(
                    "2 - Doing sync for agency ${agency.id} and reservationInterval $reservationInterval " +
                        "yachtsCount ${nausysYachtIds.size} yachts ${
                            yachtsInGroup.map { y -> y.id }.joinToString { "," }
                        }",
                )
                // create intervals for this group
                val intervals =
                    IntervalProvider.generateIntervals(
                        startDay = reservationInterval.startDay,
                        endDay = reservationInterval.endDay,
                        durationInDays = reservationInterval.duration,
                        startDate = reservationOptionsGroup.key.start,
                        endDate = syncEndDate,
                    )

                intervals.forEach { interval ->
                    val freeYachtRequest =
                        RestFreeYachtsRequest(
                            credentials = nauSysAuthProvider.auth,
                            periodFrom = NauSysDateWrapper(interval.start.format(NauSysDateWrapper.DATE_FORMATTER)),
                            periodTo = NauSysDateWrapper(interval.end.format(NauSysDateWrapper.DATE_FORMATTER)),
                            yachts = nausysYachtIds,
                            extendedDataSet = "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS",
                            // Include yachts currently under option (from any agency). Without this
                            // flag NauSys silently omits them, and end-users see a yacht that is in
                            // fact pre-reserved as if it were FREE. The returned `status` on each
                            // RestFreeYacht (OPTION / UNDER_OPTION / FREE / ...) is mapped by
                            // OfferStatus.fromNausysValue in NauSysYachtOfferSyncService.updateOffer.
                            ignoreOptions = true,
                        )
                    val response = nauSysRetryableClient.getFreeYachts(freeYachtRequest)
                    // syncOffers only performs in-response reconciliation now: for each yacht that
                    // IS in the response but missing this exact (dateFrom, dateTo), existing FREE
                    // offers for that week are flipped to OPTION_WAITING + SYNTHETIC_DISAPPEARANCE.
                    // The previous "absent-from-response" pass was removed — with single-credential
                    // NauSys, absence is too ambiguous (credential scoping vs. actual option) and
                    // produced widespread false positives on cross-agency yachts. If the response
                    // is empty, we still call syncOffers but it becomes effectively a no-op.
                    try {
                        log.trace(
                            "3 - Syncing offers for agency: ${agency.id}, interval: $interval, " +
                                "returned yachts: ${response.freeYachts?.size ?: 0}, requested: ${nausysYachtIds.size}",
                        )
                        nauSysYachtOfferSyncService.syncOffers(
                            agency,
                            response,
                            allAgencyYachts,
                            interval.start,
                            interval.end,
                            // Supplemental 7-day Sat->Sat query on a min-stay season (>7d): NauSys may
                            // not quote a 7-day price even though the boat is free, so do NOT let the
                            // disappearance pass flip genuinely-free weeks to pre-reserved.
                            skipDisappearance =
                                reservationInterval.duration == 7 && reservationOptionsGroup.key.minimalDuration > 7,
                        )
                    } catch (e: Exception) {
                        log.error(
                            "Error syncing offers for agency: ${agency.id}, interval: $interval, error: ${e.message}",
                            e,
                        )
                    }
                }
            }
        }
    }

    fun calcSyncEndDate(reservationOptionsGroup: ReservationOptionsGroup): LocalDate {
        // Cap how far ahead we generate intervals — bounded by the operator's
        // published season `end`, but extended to 18 months. The old cap
        // (offerMaxYears = 1yr) never reached the autumn-of-next-year seasons
        // NauSys actually publishes (e.g. Sep/Oct 2027), so the scheduled sync
        // skipped them entirely. The season `end` remains the real upper bound.
        val horizonCap = LocalDate.now().plusMonths(OFFER_SYNC_HORIZON_MONTHS)
        return if (reservationOptionsGroup.end.isAfter(horizonCap)) horizonCap else reservationOptionsGroup.end
    }

    fun syncOffersForYachtIdAndDateRage(
        externalYachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ) {
        val freeYachtRequest =
            RestFreeYachtsRequest(
                credentials = nauSysAuthProvider.auth,
                periodFrom = NauSysDateWrapper(dateFrom.format(NauSysDateWrapper.DATE_FORMATTER)),
                periodTo = NauSysDateWrapper(dateTo.format(NauSysDateWrapper.DATE_FORMATTER)),
                yachts = listOf(externalYachtId),
                extendedDataSet = "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS",
                // See comment in syncOffersForYachts — include under-option yachts so pre-reserved
                // periods surface on the web rather than being silently hidden by NauSys.
                ignoreOptions = true,
            )

        val response = nauSysRetryableClient.getFreeYachts(freeYachtRequest)
        if (!response.freeYachts.isNullOrEmpty()) {
            try {
                nauSysYachtOfferSyncService.syncOffersForAsync(response.freeYachts!!)
            } catch (e: Exception) {
                log.error(
                    "Error syncing offers for yacht: $externalYachtId, date range: $dateFrom to $dateTo, error: ${e.message}",
                    e,
                )
            }
        }
    }
}
