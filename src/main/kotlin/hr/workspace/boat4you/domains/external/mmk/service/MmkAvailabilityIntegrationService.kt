package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import hr.workspace.boat4you.domains.external.service.AvailabilitySyncResult
import hr.workspace.boat4you.domains.external.service.AvailabilitySyncRunSummary
import hr.workspace.boat4you.domains.external.service.PartnerAccessGuard
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
    private val partnerAccessGuard: PartnerAccessGuard,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)
    private val mmkSystemId = ExternalSystemEnum.MMK.value.toLong()

    fun syncYachtAvailability() {
        val agencies = agencyRepository.findAllActiveByPrimarySyncProviderAndHasYacht(mmkSystemId)
        val syncYears = getSyncYears()
        val summary = AvailabilitySyncRunSummary("MMK").apply { this.agencies = agencies.size }
        agencies.forEach { agency ->
            val agencyExternalId = agency.getExternalId()
            if (agencyExternalId == null) {
                log.warn("Agency external id is null for agency: ${agency.id} ${agency.name}")
                return@forEach
            }
            // Agencies the partner keeps refusing are paused (Mario rule 28.6.2026) so a
            // removed/off-boarded agency can't flood the logs run after run.
            if (partnerAccessGuard.shouldSkip(mmkSystemId, agencyExternalId)) return@forEach

            for (year in syncYears) {
                try {
                    // Retry only the FETCH (outside the @Transactional sync) and only for
                    // TRANSIENT errors — a hard "Illegal access" refusal is not retried.
                    summary.calls++
                    val response =
                        withRetry(year, agencyExternalId) { mmkAuditedClient.getAvailability(year, agencyExternalId) }
                    val agencyLabel = "agency=${agency.id} (${agency.name}) companyId=$agencyExternalId"
                    val result = mmkAvailabilitySyncService.syncYachtAvailability(agency.id!!, response, year, agencyLabel)
                    summary.record(agency.id!!, year, result)
                    logAgencyYear(agencyLabel, year, result)
                    partnerAccessGuard.recordSuccess(mmkSystemId, agencyExternalId)
                    if (agency.availabilityBlocked) {
                        // Access is back — un-hide the agency's yachts.
                        agencyRepository.setAvailabilityBlocked(agency.id!!, false)
                        agency.availabilityBlocked = false
                        log.warn("MMK access restored for agency $agencyExternalId (${agency.name}) — un-hiding its yachts")
                    }
                } catch (e: Exception) {
                    val strikes = partnerAccessGuard.recordFailure(mmkSystemId, agencyExternalId)
                    if (partnerAccessGuard.isAccessDenied(e)) {
                        val giveUp = strikes >= partnerAccessGuard.giveUpThreshold
                        if (giveUp && !agency.availabilityBlocked) {
                            // Partner has dropped us for this agency — hide its yachts
                            // (stale availability = booking risk) until access returns.
                            agencyRepository.setAvailabilityBlocked(agency.id!!, true)
                            agency.availabilityBlocked = true
                        }
                        log.warn(
                            "MMK denies access to agency $agencyExternalId (${agency.name}) (Illegal access to entity), " +
                                "strike $strikes/${partnerAccessGuard.giveUpThreshold}" +
                                if (giveUp) " — paused + hiding its yachts (re-probe in 24h)" else "",
                        )
                        break // every year is denied for this agency; don't probe the rest this run
                    }
                    log.error("Error syncing availability for agency $agencyExternalId for year $year after retries", e)
                }
            }
        }
        log.info(summary.toLogLine())
        if (summary.upsert.cannotSynthesize > 0) {
            log.warn(
                "MMK availability: ${summary.upsert.cannotSynthesize} OPTION rows could not be synthesized (no FREE template " +
                    "offer) — yachts (sample) ${summary.upsert.cannotSynthesizeYachtIds}",
            )
        }
    }

    /** Per-agency attribution line (replaces the MMK wire log as the way to see what each company
     * returned — see MmkRestClientConfig). WARN only for the real mapping-drift signal. */
    private fun logAgencyYear(
        agencyLabel: String,
        year: Int,
        result: AvailabilitySyncResult,
    ) {
        log.info("MMK availability: $agencyLabel year=$year rows=${result.partnerRows} mapped=${result.mappedRows}")
        if (result.unmappedNonEmpty) {
            log.warn(
                "MMK availability: $agencyLabel year=$year returned ${result.partnerRows} reservations but NONE map " +
                    "to our yachts — check the agency's yacht mappings",
            )
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
                // A hard refusal ("Illegal access to entity") is permanent — try once, never retry.
                if (partnerAccessGuard.isAccessDenied(e)) throw e
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
