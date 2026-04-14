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
            // Some agencies have more than 100 yachts, so we need to fetch them in chunks
            val existingAgencyYachts =
                externalMappingRepository.findAllExternalYachtIdsForAgency(
                    agency.id!!,
                    ExternalSystemEnum.NAUSYS.value.toLong(),
                )
            if (existingAgencyYachts.isEmpty()) {
                // first sync for this agency
                log.info("Doing initial sync for agency: ${agency.id} ${agency.name}")
                val yachtExternalIds = getAgencyYachtIdsFromNausys(agencyExternalId)
                yachtExternalIds.chunked(100).forEach { chunk ->
                    log.info("Doing sync for agency: ${agency.id} ${agency.name}, yacht chunk size: ${chunk.size}")
                    processYachtSync(agencyExternalId, agency, chunk)
                }
                nauSysYachtSyncService.deactivateYachtsForAgency(agency.id!!, yachtExternalIds)
            } else {
                existingAgencyYachts.chunked(100).forEach { chunk ->
                    log.info("Doing sync for agency: ${agency.id} ${agency.name}, yacht chunk size: ${chunk.size}")
                    processYachtSync(agencyExternalId, agency, chunk)
                }
                nauSysYachtSyncService.deactivateYachtsForAgency(agency.id!!, existingAgencyYachts)
            }
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
