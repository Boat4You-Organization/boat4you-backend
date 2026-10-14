package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import org.openapitools.client.mmk.model.Flexibility
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

@Service
class MmkYachtOfferIntegrationService(
    private val agencyRepository: AgencyRepository,
    private val syncConfigurationProperties: SyncConfigurationProperties,
    private val mmkYachtOfferSyncService: MmkYachtOfferSyncService,
    private val mmkYachtOfferIntegrationServiceAsync: MmkYachtOfferIntegrationServiceAsync,
    private val mmkOfferIntegrationUtils: MmkOfferIntegrationUtils,
    private val mmkRetryableClient: MmkRetryableClient,
    private val switches: RealPriceSwitches,
    private val offerRepository: OfferRepository,
    private val mmkFreeOfferReverifyService: MmkFreeOfferReverifyService,
    private val periodChecks: PartnerPeriodCheckStore,
    transactionManager: PlatformTransactionManager,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // The on-demand strike is its own short transaction, never the caller's (the per-yacht warm runs tx-free).
    private val strikeTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /** Nightly full horizon — month-anchored one-year slices, unchanged (see [fullHorizonWindows]). */
    fun yachtOfferSync(): MmkOfferSyncRunSummary =
        syncAgencies(
            MmkYachtOfferIntegrationServiceAsync.fullHorizonWindows(LocalDate.now(), syncConfigurationProperties.offerMaxYears),
            clipToWindow = false,
            label = "nightly",
        )

    /**
     * Near-term refresh: today .. today + 84 days, clipped to the window. Mario 5.9.2026 —
     * weekly searches no longer warm MMK live (search is served from the DB), so the bookable
     * 12 weeks are re-pulled from the scheduler at 10:50 / 16:50 UTC.
     */
    fun nearTermOfferSync(today: LocalDate = LocalDate.now()): MmkOfferSyncRunSummary =
        syncAgencies(
            listOf(MmkYachtOfferIntegrationServiceAsync.nearTermWindow(today)),
            clipToWindow = true,
            label = "near-term",
        )

    @Suppress("TooGenericExceptionCaught") // a timed-out or failed batch is logged, the next batch runs
    private fun syncAgencies(
        windows: List<ClosedRange<LocalDate>>,
        clipToWindow: Boolean,
        label: String,
    ): MmkOfferSyncRunSummary {
        log.info("Starting MMK offer sync ($label) windows=${windows.map { "${it.start}..${it.endInclusive}" }}")
        val summary = MmkOfferSyncRunSummary(label)

        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(ExternalSystemEnum.MMK.value.toLong())
        summary.agencies = agencies.size
        agencies.chunked(3).forEachIndexed { index, agencyBatch ->
            val futures =
                agencyBatch.map { agency ->
                    mmkYachtOfferIntegrationServiceAsync.syncOffersForAgencyYachts(
                        agency,
                        agency.getExternalId()!!,
                        windows,
                        clipToWindow,
                    )
                }
            // 15-minute per-batch timeout: a single hung agency call (partner
            // API stalls, no socket timeout fired, etc.) used to block the
            // entire 811-agency sync indefinitely. With the timeout the batch
            // logs the failure, the next batch starts, and we continue making
            // progress instead of holding a thread forever until the next cron
            // window. Each agency future already wraps its own try/catch (see
            // MmkYachtOfferIntegrationServiceAsync), so timing-out here just
            // means "give up on the slow ones and move on".
            try {
                CompletableFuture
                    .allOf(*futures.toTypedArray())
                    .orTimeout(15, java.util.concurrent.TimeUnit.MINUTES)
                    .join()
            } catch (e: Exception) {
                summary.timedOutBatches++
                log.error(
                    "MMK offer sync batch $index timed out or failed — agencies in batch: " +
                        agencyBatch.joinToString(", ") { "${it.id}/${it.name}" },
                    e,
                )
            }
            // Agencies that did finish (even inside a timed-out batch) still count.
            futures.forEach { future -> runCatching { future.getNow(null) }.getOrNull()?.let(summary::record) }
            // DEBUG (was INFO, ~270 lines/run): the run is summarised in one line by the job.
            log.debug("Finished processing batch $index of ${agencies.size} agencies")
        }
        return summary
    }

    // make option to sync yacht offers by exact dates, or by month and flexibility 5??

    /**
     * Per-yacht on-demand warm (path 4). `showOptions = true` (explicit since the real-price work; the client already
     * defaulted to it) so a week the partner holds under option answers with that option instead of empty.
     *
     * MMK_ONDEMAND_STRIKE=ON: an exact-date answer (`flexibility = 1`) that is HTTP 200 with an empty list, for a period
     * we show with exactly those dates, is the first strike of the two-day rule (DESIGN.md B1 path 4), recorded after
     * the HTTP call in its own short transaction. Never a status write from this request path; the 13:30 reverifier
     * (step 2b) asks again on a later day. A `flexibility = 6` answer is never evidence.
     */
    @Suppress("TooGenericExceptionCaught") // a failed upsert is logged, the warm never fails the request
    fun syncOffersForYachtIdAndDateRage(
        externalYachtId: Long,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
    ) {
        val syncStartDate =
            if (dateFrom != null) {
                dateFrom.atStartOfDay()
            } else {
                LocalDateTime.now().plusYears(syncConfigurationProperties.offerMaxYears.toLong())
            }
        val syncEndDate =
            if (dateTo != null) {
                dateTo.atStartOfDay()
            } else {
                LocalDateTime.now().plusYears(syncConfigurationProperties.offerMaxYears.toLong())
            }
        val flexibility = mmkOfferIntegrationUtils.getFlexibility(dateFrom, dateTo)

        val response =
            mmkRetryableClient.getOffers(
                dateFrom =
                    MmkDateTimeWrapper(
                        syncStartDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                    ),
                dateTo =
                    MmkDateTimeWrapper(
                        syncEndDate.format(MmkDateTimeWrapper.READ_FORMATTER),
                    ),
                flexibility = flexibility,
                yachtId = listOf(externalYachtId),
                showOptions = true,
            )

        if (response.isNotEmpty()) {
            try {
                mmkYachtOfferSyncService.syncOffers(response)
            } catch (e: Exception) {
                log.error(
                    "Error syncing offers for yacht: $externalYachtId, date range: $syncStartDate to $syncEndDate, error: ${e.message}",
                    e,
                )
            }
        } else if (flexibility == Flexibility._1 && switches.mmkOnDemandStrike.isOn) {
            // _1 is only chosen for a dated request (MmkOfferIntegrationUtils.getFlexibility), so both dates are set
            if (dateFrom != null && dateTo != null) recordOnDemandStrike(externalYachtId, dateFrom, dateTo)
        }
    }

    /** First strike + bookkeeping for a shown period MMK answered empty; best-effort, never fails the request path. */
    private fun recordOnDemandStrike(
        externalYachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ) {
        runCatching {
            strikeTx.executeWithoutResult {
                val today = LocalDate.now()
                offerRepository
                    .findYachtIdsShowingMmkPeriod(ExternalSystemEnum.MMK.value.toLong(), externalYachtId, dateFrom, dateTo)
                    .forEach { yachtId ->
                        mmkFreeOfferReverifyService.recordFirstStrike(yachtId, dateFrom, dateTo, today)
                        periodChecks.recordChecked(
                            ExternalSystemEnum.MMK,
                            yachtId,
                            dateFrom,
                            dateTo,
                            PeriodCheckPriority.ON_DEMAND_OR_BOOKING,
                            PeriodCheckResult.EMPTY,
                            today = today,
                        )
                        log.info("MMK on-demand: yacht=$yachtId $dateFrom..$dateTo answered empty - strike recorded, no status change")
                    }
            }
        }.onFailure { log.warn("MMK on-demand strike for externalYachtId=$externalYachtId $dateFrom..$dateTo not recorded: ${it.message}") }
    }
}
