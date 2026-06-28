package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import hr.workspace.boat4you.domains.external.service.PartnerAccessGuard
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
    private val partnerAccessGuard: PartnerAccessGuard,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)
    private val nausysSystemId = ExternalSystemEnum.NAUSYS.value.toLong()

    fun syncYachtAvailability() {
        val agencies = agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(nausysSystemId)
        val syncYears = getSyncYears()
        log.info("Doing sync for ${agencies.size} agencies")
        agencies.forEach { agency ->
            val agencyExternalId = agency.getExternalId()?.toInt()
            if (agencyExternalId == null) {
                log.error("Agency external id is null for agency: ${agency.id} ${agency.name}")
                return@forEach
            }
            // Agencies that keep failing (off-boarded / churned out) are paused so they
            // can't flood the logs run after run (Mario rule 28.6.2026; re-probe in 24h).
            if (partnerAccessGuard.shouldSkip(nausysSystemId, agencyExternalId.toLong())) return@forEach

            for (year in syncYears) {
                try {
                    val nausysResponse = nauSysAuditedClient.getOccupancyByYear(agencyExternalId, year)
                    val reservationCount = nausysResponse.reservations?.size ?: 0
                    log.info(
                        "NauSYS availability: agency=${agency.id} (${agency.name}) extId=$agencyExternalId " +
                            "year=$year reservations=$reservationCount",
                    )
                    nauSysAvailabilitySyncService.syncYachtAvailability(agency.id!!, nausysResponse, year)
                    partnerAccessGuard.recordSuccess(nausysSystemId, agencyExternalId.toLong())
                } catch (ex: Exception) {
                    val strikes = partnerAccessGuard.recordFailure(nausysSystemId, agencyExternalId.toLong())
                    if (strikes >= partnerAccessGuard.giveUpThreshold) {
                        log.warn(
                            "NauSYS availability keeps failing for agency=${agency.id} (${agency.name}) " +
                                "extId=$agencyExternalId — pausing it after $strikes strikes (re-probe in 24h)",
                        )
                        break // stop probing the remaining years for this agency this run
                    }
                    log.error(
                        "NauSYS availability FAILED for agency=${agency.id} (${agency.name}) " +
                            "extId=$agencyExternalId year=$year — rolled back; continuing with next",
                        ex,
                    )
                }
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
