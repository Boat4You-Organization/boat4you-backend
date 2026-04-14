package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class NauSysAvailabilityIntegrationService(
    private val nauSysAvailabilitySyncService: NauSysAvailabilitySyncService,
    private val agencyRepository: AgencyRepository,
    private val syncConfigurationProperties: SyncConfigurationProperties,
    private val nauSysAuditedClient: NauSysAuditedClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun syncYachtAvailability() {
        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(ExternalSystemEnum.NAUSYS.value.toLong())
        val syncYears = getSyncYears()
        log.info("Doing sync for ${agencies.size} agencies")
        agencies.forEach {
            val agencyExternalId = it.getExternalId()?.toInt()
            if (agencyExternalId != null) {
                syncYears.forEach { year ->
                    val nausysResponse = nauSysAuditedClient.getOccupancyByYear(agencyExternalId, year)

                    nauSysAvailabilitySyncService.syncYachtAvailability(it.id!!, nausysResponse)
                }
            } else {
                log.error("Agency external id is null for agency: ${it.id} ${it.name}")
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
