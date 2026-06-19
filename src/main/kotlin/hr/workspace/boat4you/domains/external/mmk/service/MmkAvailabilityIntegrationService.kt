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
                        // Retry only the FETCH (outside the @Transactional sync): MMK intermittently
                        // returns 400 "Illegal access to entity" for companies it otherwise serves, so
                        // without a retry the agency is skipped for the whole run and its availability
                        // goes stale until a later run happens to succeed. The DB write is NOT retried.
                        val response =
                            withRetry(year, agencyExternalId) { mmkAuditedClient.getAvailability(year, agencyExternalId) }
                        mmkAvailabilitySyncService.syncYachtAvailability(it.id!!, response, year)
                    } catch (e: Exception) {
                        log.error("Error syncing availability for agency $agencyExternalId for year $year after retries", e)
                    }
                }
            } else {
                log.warn("Agency external id is null for agency: ${it.id} ${it.name}")
            }
        }
    }

    private fun <T> withRetry(
        year: Int,
        companyId: Long,
        attempts: Int = 3,
        block: () -> T,
    ): T {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) {
                    log.warn(
                        "MMK availability fetch failed for company $companyId year $year " +
                            "(attempt ${i + 1}/$attempts): ${e.message}; retrying",
                    )
                    Thread.sleep(800L * (i + 1))
                }
            }
        }
        throw last!!
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
