package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import hr.workspace.boat4you.domains.external.service.AvailabilitySyncResult
import hr.workspace.boat4you.domains.external.service.AvailabilitySyncRunSummary
import hr.workspace.boat4you.domains.external.service.PartnerAccessGuard
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpServerErrorException
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
        val summary = AvailabilitySyncRunSummary("NauSYS").apply { this.agencies = agencies.size }
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
                    summary.calls++
                    val nausysResponse = nauSysAuditedClient.getOccupancyByYear(agencyExternalId, year)
                    val agencyLabel = "agency=${agency.id} (${agency.name}) extId=$agencyExternalId"
                    val result = nauSysAvailabilitySyncService.syncYachtAvailability(agency.id!!, nausysResponse, year, agencyLabel)
                    summary.record(agency.id!!, year, result)
                    logAgencyYear(agencyLabel, year, result)
                    partnerAccessGuard.recordSuccess(nausysSystemId, agencyExternalId.toLong())
                } catch (ex: Exception) {
                    // A partner-side throttle / outage is NOT an agency problem: never let it
                    // count toward the 2-strike pause (which would silence the agency's
                    // occupancy feed for 24 h). The next pass (≤6 h) re-probes it.
                    if (ex is NauSysRateLimitedException || ex is HttpServerErrorException) {
                        log.warn(
                            "NauSYS availability transient failure for agency=${agency.id} (${agency.name}) " +
                                "extId=$agencyExternalId year=$year — no strike, retried next pass: $ex",
                        )
                        continue
                    }
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
        log.info(summary.toLogLine())
        if (summary.upsert.cannotSynthesize > 0) {
            log.warn(
                "NauSYS availability: ${summary.upsert.cannotSynthesize} OPTION rows could not be synthesized (no FREE template " +
                    "offer) — yachts (sample) ${summary.upsert.cannotSynthesizeYachtIds}",
            )
        }
    }

    /** Per-agency attribution line; WARN only for the real mapping-drift signal (rows but none mapped). */
    private fun logAgencyYear(
        agencyLabel: String,
        year: Int,
        result: AvailabilitySyncResult,
    ) {
        log.info("NauSYS availability: $agencyLabel year=$year reservations=${result.partnerRows} mapped=${result.mappedRows}")
        if (result.unmappedNonEmpty) {
            log.warn(
                "NauSYS availability: $agencyLabel year=$year returned ${result.partnerRows} reservations but NONE map " +
                    "to our yachts — check the agency's yacht mappings",
            )
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
