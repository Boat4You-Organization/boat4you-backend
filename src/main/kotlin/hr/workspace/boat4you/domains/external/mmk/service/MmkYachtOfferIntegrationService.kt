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

    fun yachtOfferSync() {
        log.info("Starting MMK offer sync")

        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(ExternalSystemEnum.MMK.value.toLong())
        agencies.chunked(3).forEachIndexed { index, agencyBatch ->
            val futures =
                agencyBatch.map { agency ->
                    mmkYachtOfferIntegrationServiceAsync.syncOffersForAgencyYachts(
                        agency,
                        agency.getExternalId()!!,
                    )
                }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
            log.info("Finished processing batch $index of ${agencies.size} agencies")
        }
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
