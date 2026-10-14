package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.common.test.ProdTestSamples.DREAM_YACHT_AGENCY_ID
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.evidence.ReverifyRunStore
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.model.ReservationOptionsGroup
import hr.workspace.boat4you.domains.external.model.SyncInterval
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.IntervalProvider
import hr.workspace.boat4you.domains.external.service.ReservationOptionsCombinationProvider
import hr.workspace.boat4you.domains.external.service.YachtGroupingProvider
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import org.openapitools.client.nausys.model.RestFreeYacht
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

private const val OFFER_SYNC_HORIZON_MONTHS = 18L
private const val DEFAULT_DRAIN_ROWS = 500

/** The `extendedDataSet` of every NauSys grid / exact-date `getFreeYachts` call (prices, payment plans, extras). */
internal const val NAUSYS_GRID_EXTENDED_DATA_SET = "PAYMENT_PLAN,OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS"

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
    private val retryQueue: NausysOfferSyncRetryQueue,
    private val switches: RealPriceSwitches,
    private val runStore: ReverifyRunStore,
    private val offerRepository: OfferRepository,
    private val periodChecks: PartnerPeriodCheckStore,
    transactionManager: PlatformTransactionManager,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // Path-4 evidence is its own short transaction, never the caller's (the per-yacht warm runs tx-free).
    private val evidenceTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /**
     * Full per-agency offer grid (Saturday weeks + min-stay slots for every allowed
     * check-in/out pair) up to [horizonEnd].
     *
     * @param horizonEnd upper bound for generated intervals. `null` (the nightly) keeps the
     * 18-month / season-end cap unchanged. The near-term refresh passes today + 12 weeks:
     * Mario 5.9.2026 — search is served from the DB (weekly ranges no longer warm partners
     * live), so intraday price/availability freshness for the bookable window comes from
     * re-running this same grid, bounded, twice a day. Nothing outside the bound is read
     * or written: every write in [NauSysYachtOfferSyncService.syncOffers] is keyed to one
     * generated (dateFrom, dateTo) interval, so disappearance semantics are identical.
     */
    fun yachtOfferSync(horizonEnd: LocalDate? = null): NauSysOfferSyncRunSummary {
        log.info("Starting NauSYS offer sync${horizonEnd?.let { " (horizon <= $it)" } ?: ""}")
        val summary = NauSysOfferSyncRunSummary()
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())

        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(ExternalSystemEnum.NAUSYS.value.toLong())
        summary.agencies = agencies.size
        log.info("Doing sync for ${agencies.size} agencies")
        // D3 (NAUSYS_D3_MODE), read once per pass: LEGACY = no run, today's disappearance pass.
        val d3 = openD3Run()
        try {
            agencies.forEach { agency ->
                try {
                    syncOffersForYachts(agency, externalSystem, horizonEnd, summary, d3)
                } catch (e: Exception) {
                    summary.agencyFailures++
                    log.error("Error while syncing Nausys Offers ${agency.id}", e)
                }
            }
        } finally {
            d3?.finish(if (horizonEnd == null) "nightly" else "near-term")
        }
        log.info("Finished NauSYS offer sync: ${summary.toLogLine()}")
        return summary
    }

    /** A NAUSYS_D3 run for LOG / WRITE, null for LEGACY; a failure to open one falls back to LEGACY for this pass. */
    private fun openD3Run(): NauSysD3Run? =
        runCatching { NauSysD3Run.open(runStore, switches.nausysD3Mode) }
            .onFailure { log.error("NauSys D3 run could not be opened - this pass runs the LEGACY disappearance pass", it) }
            .getOrNull()

    /***
     * Syncs offers for yachts.
     * Looks for all yachts in a given agency and groups them by their reservation options.
     * For each group, it generates valid combinations of check-in and check-out days,
     * and creates intervals for each combination.
     */
    private fun syncOffersForYachts(
        agency: Agency,
        externalSystem: ExternalSystem,
        horizonEnd: LocalDate?,
        summary: NauSysOfferSyncRunSummary,
        d3: NauSysD3Run?,
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

            val syncEndDate = calcSyncEndDate(reservationOptionsGroup.key, horizonEnd)

            // DEBUG (was INFO): per-group / per-slot lines are per-row noise under the Part 4
            // logging discipline; the run is summarised in one line by the caller.
            log.debug(
                "1 - Doing sync for agency ${agency.id} startDate ${reservationOptionsGroup.key.start} syncEndDate $syncEndDate " +
                    "yachtsCount ${nausysYachtIds.size} yachts ${
                        yachtsInGroup.map { y -> y.id }.joinToString { "," }
                    }",
            )

            val reservationIntervals =
                ReservationOptionsCombinationProvider.generateValidCombinations(reservationOptionsGroup.key)
            reservationIntervals.forEach { reservationInterval ->
                log.debug(
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

                syncIntervals(
                    agency,
                    allAgencyYachts,
                    nausysYachtIds,
                    intervals,
                    // Supplemental 7-day Sat->Sat query on a min-stay season (>7d): NauSys may
                    // not quote a 7-day price even though the boat is free, so do NOT let the
                    // disappearance pass flip genuinely-free weeks to pre-reserved.
                    skipDisappearance = reservationInterval.duration == 7 && reservationOptionsGroup.key.minimalDuration > 7,
                    summary = summary,
                    d3 = d3,
                )
            }
        }
    }

    /**
     * Fetches + syncs one week interval at a time. The partner call is INSIDE the
     * per-interval try so one failed week (429 budget exhausted, 5xx, parse error)
     * no longer aborts the rest of the agency for the night — it is queued in
     * `nausys_offer_sync_retry` and re-fetched by [drainRetryQueue].
     */
    @Suppress("LongParameterList") // the trailing summary keeps the 5-arg call sites (and their tests) intact
    internal fun syncIntervals(
        agency: Agency,
        allAgencyYachts: List<Yacht>,
        nausysYachtIds: List<Long>,
        intervals: List<SyncInterval>,
        skipDisappearance: Boolean,
        summary: NauSysOfferSyncRunSummary = NauSysOfferSyncRunSummary(),
        d3: NauSysD3Run? = null,
    ) {
        intervals.forEach { interval ->
            summary.intervals++
            try {
                val response = nauSysRetryableClient.getFreeYachts(freeYachtsRequest(nausysYachtIds, interval.start, interval.end))
                summary.offersReturned += response.freeYachts?.size ?: 0
                // syncOffers only performs in-response reconciliation now: for each yacht that
                // IS in the response but missing this exact (dateFrom, dateTo), existing FREE
                // offers for that week are flipped to OPTION_WAITING + SYNTHETIC_DISAPPEARANCE.
                // The previous "absent-from-response" pass was removed — with single-credential
                // NauSys, absence is too ambiguous (credential scoping vs. actual option) and
                // produced widespread false positives on cross-agency yachts. If the response
                // is empty, we still call syncOffers but it becomes effectively a no-op.
                log.trace(
                    "3 - Syncing offers for agency: ${agency.id}, interval: $interval, " +
                        "returned yachts: ${response.freeYachts?.size ?: 0}, requested: ${nausysYachtIds.size}",
                )
                summary.disappeared +=
                    nauSysYachtOfferSyncService.syncOffers(
                        agency,
                        response,
                        allAgencyYachts,
                        interval.start,
                        interval.end,
                        skipDisappearance = skipDisappearance,
                        d3 = d3,
                    )
            } catch (e: Exception) {
                summary.deferred++
                runCatching {
                    retryQueue.enqueue(agency.id!!, interval.start, interval.end, nausysYachtIds, skipDisappearance, e)
                }.onFailure { qe -> log.error("Could not enqueue NauSYS offer retry for agency ${agency.id} $interval", qe) }
                if (isTransientPartnerFailure(e)) {
                    log.warn("NauSYS offers for agency ${agency.id} interval $interval deferred to the retry queue: $e")
                } else {
                    log.error("Error syncing offers for agency: ${agency.id}, interval: $interval, error: ${e.message} — queued for retry", e)
                }
            }
        }
    }

    private fun freeYachtsRequest(
        nausysYachtIds: List<Long>,
        from: LocalDate,
        to: LocalDate,
    ) = RestFreeYachtsRequest(
        credentials = nauSysAuthProvider.auth,
        periodFrom = NauSysDateWrapper(from.format(NauSysDateWrapper.DATE_FORMATTER)),
        periodTo = NauSysDateWrapper(to.format(NauSysDateWrapper.DATE_FORMATTER)),
        yachts = nausysYachtIds,
        extendedDataSet = NAUSYS_GRID_EXTENDED_DATA_SET,
        // Include yachts currently under option (from any agency). Without this
        // flag NauSys silently omits them, and end-users see a yacht that is in
        // fact pre-reserved as if it were FREE. The returned `status` on each
        // RestFreeYacht (OPTION / UNDER_OPTION / FREE / ...) is mapped by
        // OfferStatus.fromNausysValue in NauSysYachtOfferSyncService.updateOffer.
        ignoreOptions = true,
    )

    /**
     * Re-fetches queued (agency, interval) rows whose `next_attempt_at` has passed.
     * Called at the end of the nightly run (once cusma2's morning warm peak is over)
     * and in the 06:15 / 10:15 / 15:15 backup slots. Success deletes the row; a
     * failure pushes it 15 min × attempts out and gives up after 6 attempts.
     */
    fun drainRetryQueue(maxRows: Int = DEFAULT_DRAIN_ROWS) {
        val rows = retryQueue.due(maxRows)
        if (rows.isEmpty()) {
            log.info("NauSYS offer retry queue: nothing due")
            return
        }
        log.info("NauSYS offer retry queue: draining ${rows.size} rows")
        val d3 = openD3Run()
        try {
            drainRows(rows, d3)
        } finally {
            d3?.finish("retry drain")
        }
    }

    private fun drainRows(
        rows: List<hr.workspace.boat4you.domains.external.sync.jpa.NausysOfferSyncRetry>,
        d3: NauSysD3Run?,
    ) {
        var ok = 0
        var failed = 0
        var gaveUp = 0
        for (row in rows) {
            val agency = agencyRepository.findById(row.agencyId!!).orElse(null)
            if (agency == null) {
                log.warn("NauSYS offer retry: agency ${row.agencyId} no longer exists — dropping row ${row.id}")
                retryQueue.markSuccess(row)
                continue
            }
            val from = row.periodFrom!!
            val to = row.periodTo!!
            try {
                val response = nauSysRetryableClient.getFreeYachts(freeYachtsRequest(row.yachtExternalIdList(), from, to))
                nauSysYachtOfferSyncService.syncOffers(
                    agency,
                    response,
                    yachtRepository.findWithReservationOptionsByAgency(agency),
                    from,
                    to,
                    skipDisappearance = row.skipDisappearance,
                    d3 = d3,
                )
                retryQueue.markSuccess(row)
                ok++
            } catch (e: Exception) {
                if (retryQueue.markFailure(row, e)) gaveUp++ else failed++
                log.warn("NauSYS offer retry failed agency=${agency.id} $from→$to (attempt ${row.attempts}): $e")
            }
        }
        log.info("NauSYS offer retry queue: ok=$ok failed=$failed gaveUp=$gaveUp")
    }

    private fun isTransientPartnerFailure(e: Throwable): Boolean = e is NauSysRateLimitedException || e is HttpServerErrorException || e is ResourceAccessException

    /**
     * @param horizonEnd explicit cap (near-term refresh: today + 12 weeks). `null` = the
     * nightly's 18-month cap. Either way the operator's season `end` stays the real upper bound.
     */
    fun calcSyncEndDate(
        reservationOptionsGroup: ReservationOptionsGroup,
        horizonEnd: LocalDate? = null,
    ): LocalDate {
        // Cap how far ahead we generate intervals — bounded by the operator's
        // published season `end`, but extended to 18 months. The old cap
        // (offerMaxYears = 1yr) never reached the autumn-of-next-year seasons
        // NauSys actually publishes (e.g. Sep/Oct 2027), so the scheduled sync
        // skipped them entirely. The season `end` remains the real upper bound.
        val horizonCap = horizonEnd ?: LocalDate.now().plusMonths(OFFER_SYNC_HORIZON_MONTHS)
        return if (reservationOptionsGroup.end.isAfter(horizonCap)) horizonCap else reservationOptionsGroup.end
    }

    /**
     * Per-yacht on-demand warm (path 4): one exact-date `getFreeYachts` for one NauSys yacht, upserted through
     * [NauSysYachtOfferSyncService.syncOffersForAsync] (no disappearance pass).
     *
     * `NAUSYS_REVERIFY` LOG / WRITE (DESIGN.md B2 path 4): only an answer with `status == "OK"` is used; any other body
     * is UNKNOWN (logged, nothing written). An OK answer that is empty, or FREE without a price, for exactly these
     * dates while we show a row with exactly these dates records a priority-3 strike in `partner_period_check`, and an
     * OK answer that quotes the yacht only on another route records ROUTE_GONE for the period - both in their own
     * short transaction after the HTTP call, never a status write (the 17:30 reverifier decides on a later day).
     * OFF = today's behaviour. The multi-yacht location warm is never evidence (it does not come through here).
     */
    @Suppress("TooGenericExceptionCaught") // a failed upsert is logged, the warm never fails the request
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
                extendedDataSet = NAUSYS_GRID_EXTENDED_DATA_SET,
                // See comment in syncOffersForYachts — include under-option yachts so pre-reserved
                // periods surface on the web rather than being silently hidden by NauSys.
                ignoreOptions = true,
            )

        val response = nauSysRetryableClient.getFreeYachts(freeYachtRequest)
        val evidence = switches.nausysReverify != NauSysReverifyMode.OFF
        if (evidence && response.status != NAUSYS_OK) {
            log.info(
                "NauSys on-demand: externalYachtId=$externalYachtId $dateFrom..$dateTo answered status=${response.status} " +
                    "errorCode=${response.errorCode} - unknown, nothing written",
            )
            return
        }
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
        if (evidence) recordOnDemandEvidence(externalYachtId, dateFrom, dateTo, response)
    }

    /** Negative evidence of an OK exact-date answer for a period we show; best-effort, never fails the request path. */
    private fun recordOnDemandEvidence(
        externalYachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        response: RestFreeYachtList,
    ) {
        runCatching {
            val nausys = ExternalSystemEnum.NAUSYS.value.toLong()
            val shown = offerRepository.findNauSysShownPeriodRows(nausys, externalYachtId, dateFrom, dateTo)
            if (shown.isEmpty()) return
            val exact =
                response.freeYachts.orEmpty().filter {
                    it.yachtId == externalYachtId && it.periodFrom?.value == dateFrom && it.periodTo?.value == dateTo
                }
            val result = onDemandResult(exact, shown.map { it.locationFromId to it.locationToId }) ?: return
            val today = LocalDate.now()
            evidenceTx.executeWithoutResult {
                shown.map { it.yachtId }.distinct().forEach { yachtId ->
                    periodChecks.recordFirstStrike(
                        ExternalSystemEnum.NAUSYS,
                        yachtId,
                        dateFrom,
                        dateTo,
                        PeriodCheckPriority.ON_DEMAND_OR_BOOKING,
                        result,
                        today,
                    )
                    log.info("NauSys on-demand: yacht=$yachtId $dateFrom..$dateTo answered $result - strike recorded, no status change")
                }
            }
        }.onFailure { log.warn("NauSys on-demand evidence for externalYachtId=$externalYachtId $dateFrom..$dateTo not recorded: ${it.message}") }
    }

    /**
     * EMPTY (no row for these dates), UNPRICED (only FREE rows without a price), ROUTE_GONE (quoted, but none of the
     * shown routes among the quoted ones, all quoted locations mapped), or null = no negative evidence.
     */
    private fun onDemandResult(
        exact: List<RestFreeYacht>,
        shownRoutes: List<Pair<Long, Long>>,
    ): PeriodCheckResult? {
        if (exact.isEmpty()) return PeriodCheckResult.EMPTY
        val quoted = exact.filterNot(::isFreeUnpriced)
        if (quoted.isEmpty()) return PeriodCheckResult.UNPRICED
        val locations = locationMappings()
        val quotedRoutes =
            quoted.map { q ->
                val from = locations[q.locationFromId] ?: return null // our mapping gap: not evidence
                val to = locations[q.locationToId] ?: return null
                from to to
            }
        val anyShownQuoted = shownRoutes.any { (f, t) -> quotedRoutes.any { (qf, qt) -> f in qf && t in qt } }
        return if (anyShownQuoted) null else PeriodCheckResult.ROUTE_GONE
    }

    /** NauSys location id -> our location ids (a partner location may be mapped to more than one of ours). */
    private fun locationMappings(): Map<Long?, Set<Long>> {
        val nausys = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        return externalMappingService
            .getCachedAllMappingsByType(Location::class.simpleName.toString(), nausys)
            .filter { it.systemId != null }
            .groupBy({ it.externalId }, { it.systemId!! })
            .mapValues { it.value.toSet() }
    }

    private fun isFreeUnpriced(r: RestFreeYacht): Boolean {
        val price = r.price?.clientPrice?.toBigDecimalOrNull()
        return r.status == NAUSYS_FREE && (price == null || price.signum() <= 0)
    }

    private companion object {
        const val NAUSYS_OK = "OK"
        const val NAUSYS_FREE = "FREE"
    }
}
