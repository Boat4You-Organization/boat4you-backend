package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

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
                flexibility = mmkOfferIntegrationUtils.getFlexibility(dateFrom, dateTo),
                yachtId = listOf(externalYachtId),
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
        }
    }
}
