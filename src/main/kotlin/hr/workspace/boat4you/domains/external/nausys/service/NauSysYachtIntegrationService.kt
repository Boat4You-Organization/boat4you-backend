package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.common.test.ProdTestSamples.DREAM_YACHT_AGENCY_ID
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.openapitools.client.nausys.model.AllYachtsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NauSysYachtIntegrationService(
    private val agencyRepository: AgencyRepository,
    private val externalMappingRepository: ExternalMappingRepository,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val nauSysYachtSyncService: NauSysYachtSyncService,
    private val nauSysAuditedClient: NauSysAuditedClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    fun yachtSync() {
        val agencies = agencyRepository.findAllActiveByPrimarySyncProvider(ExternalSystemEnum.NAUSYS.value.toLong())
        log.info("Doing sync for ${agencies.size} agencies")
        agencies.forEach { agency ->
            val agencyExternalId = agency.getExternalId()
            if (agencyExternalId == null) {
                log.error("Agency external id is null for agency: ${agency.id} ${agency.name}")
                return@forEach
            }
            // ALWAYS fetch the current authoritative catalogue from NauSys —
            // never our DB subset. Two bugs were buried in the old shape:
            //   1) chunking `existingAgencyYachts` meant we only re-fetched
            //      yachts we already knew about → newly-added yachts on the
            //      partner side never surfaced (e.g. Istion / White Pearl,
            //      observed 2026-05-28; boataround had it, we didn't).
            //   2) `deactivateYachtsForAgency(agency.id, existingAgencyYachts)`
            //      compared our DB list against itself → take-back never
            //      fired and yachts moved off a partner agency stayed
            //      "live" in the listing forever.
            // The new shape fetches the live ID list, syncs each chunk, then
            // uses that list as the deactivation baseline.
            val existingAgencyYachts =
                externalMappingRepository.findAllExternalYachtIdsForAgency(
                    agency.id!!,
                    ExternalSystemEnum.NAUSYS.value.toLong(),
                )
            val partnerYachtIds = getAgencyYachtIdsFromNausys(agencyExternalId)
            if (existingAgencyYachts.isEmpty()) {
                log.info("Doing initial sync for agency: ${agency.id} ${agency.name} (${partnerYachtIds.size} partner yachts)")
            } else {
                log.info(
                    "Doing daily sync for agency: ${agency.id} ${agency.name} " +
                        "(${partnerYachtIds.size} partner yachts, ${existingAgencyYachts.size} in DB)",
                )
            }
            partnerYachtIds.chunked(100).forEach { chunk ->
                log.info("Sync chunk for agency ${agency.id}: ${chunk.size} yacht ids")
                processYachtSync(agencyExternalId, agency, chunk)
            }
            nauSysYachtSyncService.deactivateYachtsForAgency(agency.id!!, partnerYachtIds)
        }
    }

    private fun getAgencyYachtIdsFromNausys(agencyExternalId: Long): List<Long> {
        val allYachtsRequest =
            AllYachtsRequest(
                username = nauSysAuthProvider.nauSysUsername!!,
                password = nauSysAuthProvider.nauSysPassword!!,
                yachtIDs = null,
                onlyIDs = true,
            )

        val nausysResponse = nauSysAuditedClient.allYachts(agencyExternalId, allYachtsRequest)
        return nausysResponse.yachtIDs ?: emptyList()
    }

    private fun processYachtSync(
        agencyExternalId: Long,
        agency: Agency,
        chunk: List<Long>? = null,
    ) {
        try {
            val allYachtsRequest =
                AllYachtsRequest(
                    username = nauSysAuthProvider.nauSysUsername!!,
                    password = nauSysAuthProvider.nauSysPassword!!,
                    yachtIDs = chunk,
                    onlyIDs = false,
                )
            val nausysResponse = nauSysAuditedClient.allYachts(agencyExternalId, allYachtsRequest)

            if (chunk.isNullOrEmpty() && nausysResponse.yachts.isNullOrEmpty()) {
                log.trace("No Yachts found for ${agency.id}")
                return
            }
            nauSysYachtSyncService.syncYachtsForAgency(agency.id!!, nausysResponse)
        } catch (e: Exception) {
            log.error("Error syncing yachts for agency: ${agency.id} ${agency.name}", e)
        }
    }
}
