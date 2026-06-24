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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CompletableFuture

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
        return if (reservationOptionsGroup.end.isAfter(
                LocalDate.now().plusYears(syncConfigurationProperties.offerMaxYears.toLong()),
            )
        ) {
            LocalDate.now().plusYears(syncConfigurationProperties.offerMaxYears.toLong())
        } else {
            reservationOptionsGroup.end
        }
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

    /**
     * Weekly 7-day "fill" for a single NauSys yacht.
     *
     * The standard offer sync ([syncOffersForYachts]) generates intervals from the
     * operator's reservationOptions, whose interval duration is the season's
     * `minimalDuration`. In shoulder / autumn seasons that minimal duration is
     * often 14 or 28 days, so NO 7-day Sat→Sat intervals are generated there — the
     * boat then shows only ~one week per month even though it is FREE. NauSys's
     * `freeYachts` endpoint, however, quotes a 7-day price for every free week
     * (verified 24.6.2026 — matches competitor listings exactly).
     *
     * This pass asks NauSys for every Saturday→Saturday week up to [horizonEnd]
     * and upserts the result via the normal offer sync. NauSys is the source of
     * truth: weeks it does not return (truly unavailable / hard minimum-stay)
     * create nothing. 7-day Sat→Sat offers are typed STANDARD by
     * `OfferType.getFromDates`, so they surface in the calendar.
     *
     * @return the number of weeks for which NauSys returned an offer.
     */
    fun syncWeeklyOffersForYacht(
        externalYachtId: Long,
        horizonEnd: LocalDate,
    ): Int {
        var weekStart = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        var filledWeeks = 0
        while (weekStart.isBefore(horizonEnd)) {
            val weekEnd = weekStart.plusDays(7)
            try {
                val request =
                    RestFreeYachtsRequest(
                        credentials = nauSysAuthProvider.auth,
                        periodFrom = NauSysDateWrapper(weekStart.format(NauSysDateWrapper.DATE_FORMATTER)),
                        periodTo = NauSysDateWrapper(weekEnd.format(NauSysDateWrapper.DATE_FORMATTER)),
                        yachts = listOf(externalYachtId),
                        extendedDataSet = "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS",
                        ignoreOptions = true,
                    )
                val response = nauSysRetryableClient.getFreeYachts(request)
                if (!response.freeYachts.isNullOrEmpty()) {
                    nauSysYachtOfferSyncService.syncOffersForAsync(response.freeYachts!!)
                    filledWeeks++
                }
            } catch (e: Exception) {
                log.warn("weekly-offer-fill: yacht $externalYachtId week $weekStart failed: ${e.message}")
            }
            weekStart = weekStart.plusWeeks(1)
        }
        log.info("weekly-offer-fill: yacht $externalYachtId filled $filledWeeks weeks up to $horizonEnd")
        return filledWeeks
    }

    /**
     * Full-catalog weekly 7-day fill for ALL NauSys yachts (the scheduled rollout
     * of [syncWeeklyOffersForYacht]). For every Saturday→Saturday week up to
     * [horizonEnd] it asks NauSys `getFreeYachts` once per [chunkSize]-sized batch
     * of yacht ids — so the whole fleet is covered with ~`weeks * ceil(N/chunk)`
     * calls, not one-per-yacht-per-week. `syncOffersForAsync` maps each returned
     * yacht back to ours, so no per-agency context is needed. STANDARD 7-day
     * offers fill the gaps the reservation-options sync skips (operator min-stay
     * seasons), so every free week becomes visible. Idempotent (upsert).
     *
     * @return number of (yacht, week) offers created/updated.
     */
    fun weeklyOfferFillAllNauSys(
        horizonEnd: LocalDate,
        chunkSize: Int,
    ): Int {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val allNausysExternalIds =
            externalMappingService
                .getCachedAllMappingsByType(Yacht::class.simpleName.toString(), externalSystem)
                .mapNotNull { it.externalId }
                .distinct()
        if (allNausysExternalIds.isEmpty()) {
            log.warn("weekly-offer-fill ALL: no NauSys yacht mappings, nothing to do")
            return 0
        }
        log.info(
            "weekly-offer-fill ALL: ${allNausysExternalIds.size} NauSys yachts, horizon $horizonEnd, chunkSize $chunkSize",
        )
        var weekStart = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        var weekCount = 0
        var totalOfferWeeks = 0
        while (weekStart.isBefore(horizonEnd)) {
            val weekEnd = weekStart.plusDays(7)
            allNausysExternalIds.chunked(chunkSize).forEach { idsChunk ->
                try {
                    val request =
                        RestFreeYachtsRequest(
                            credentials = nauSysAuthProvider.auth,
                            periodFrom = NauSysDateWrapper(weekStart.format(NauSysDateWrapper.DATE_FORMATTER)),
                            periodTo = NauSysDateWrapper(weekEnd.format(NauSysDateWrapper.DATE_FORMATTER)),
                            yachts = idsChunk,
                            extendedDataSet = "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS",
                            ignoreOptions = true,
                        )
                    val response = nauSysRetryableClient.getFreeYachts(request)
                    if (!response.freeYachts.isNullOrEmpty()) {
                        nauSysYachtOfferSyncService.syncOffersForAsync(response.freeYachts!!)
                        totalOfferWeeks += response.freeYachts!!.size
                    }
                } catch (e: Exception) {
                    log.warn("weekly-offer-fill ALL: week $weekStart chunk(${idsChunk.size}) failed: ${e.message}")
                }
            }
            weekStart = weekStart.plusWeeks(1)
            weekCount++
            if (weekCount % 10 == 0) {
                log.info("weekly-offer-fill ALL: $weekCount weeks done, $totalOfferWeeks offer-weeks so far")
            }
        }
        log.info("weekly-offer-fill ALL: DONE — $weekCount weeks, $totalOfferWeeks offer-weeks up to $horizonEnd")
        return totalOfferWeeks
    }
}
