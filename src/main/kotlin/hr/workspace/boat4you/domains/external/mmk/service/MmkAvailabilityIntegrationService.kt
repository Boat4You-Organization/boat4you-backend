package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class MmkAvailabilityIntegrationService(
    private val agencyRepository: AgencyRepository,
    private val syncConfigurationProperties: SyncConfigurationProperties,
    private val mmkAvailabilitySyncService: MmkAvailabilitySyncService,
    private val mmkAuditedClient: MmkAuditedClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun syncYachtAvailability() {
        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(ExternalSystemEnum.MMK.value.toLong())
        val syncYears = getSyncYears()
        agencies.forEach {
            val agencyExternalId = it.getExternalId()
            if (agencyExternalId != null) {
                syncYears.forEach { year ->
                    log.info("syncing $agencyExternalId for year $year")
                    try {
                        val response = mmkAuditedClient.getAvailability(year, agencyExternalId)
                        mmkAvailabilitySyncService.syncYachtAvailability(it.id!!, response, year)
                    } catch (e: Exception) {
                        log.error("Error syncing availability for agency $agencyExternalId for year $year", e)
                    }
                }
            } else {
                log.warn("Agency external id is null for agency: ${it.id} ${it.name}")
            }
        }
    }

    private fun getSyncYears(): List<Int> {
        val currentYear = LocalDate.now().year
        val syncYears = mutableListOf<Int>()
        for (i in 0..syncConfigurationProperties.offerMaxYears) {
            syncYears.add(currentYear + i)
        }
        return syncYears
    }
}
