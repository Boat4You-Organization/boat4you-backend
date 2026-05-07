package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import org.openapitools.client.mmk.api.BookingApi
import org.openapitools.client.mmk.model.SupportedLanguagesEnum
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MmkYachtIntegrationService(
    private val agencyRepository: AgencyRepository,
    private val mmkYachtSyncService: MmkYachtSyncService,
    private val mmkAuditedClient: MmkAuditedClient,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    companion object {
        val SUPPORTED_LANGUAGES =
            setOf<SupportedLanguagesEnum>(
                SupportedLanguagesEnum.FR,
                SupportedLanguagesEnum.DE,
                SupportedLanguagesEnum.PT,
                SupportedLanguagesEnum.IT,
                SupportedLanguagesEnum.ES,
                SupportedLanguagesEnum.HR,
            )
    }

    fun yachtSync() {
        val agencies =
            agencyRepository
                .findAllActiveByPrimarySyncProvider(ExternalSystemEnum.MMK.value.toLong())
        val agenciesWithoutYachts =
            agencyRepository
                .findAllActiveWithoutYachts(ExternalSystemEnum.MMK.value.toLong())
        agencies.forEach { agency ->
            val agencyExternalId = agency.getExternalId()
            log.info("Doing sync for agency: ${agency.id} ${agency.name} externalId: $agencyExternalId")
            if (agencyExternalId == null) {
                log.error("Agency external id is null for agency: ${agency.id} ${agency.name}")
                return@forEach
            }

            processYachtSync(agencyExternalId, agency, agenciesWithoutYachts)
        }
    }

    private fun processYachtSync(
        agencyExternalId: Long,
        agency: Agency,
        agenciesWithoutYachts: Set<Long>,
    ) {
        try {
            // inventory=raw triggers MMK to populate Yacht.equipmentRaw with
            // the full per-yacht equipment list (id, parentId, name, value,
            // categoryName) instead of the slim Yacht.equipment list. The
            // slim list returns only ~8-10 entries per yacht; raw returns
            // ~25-30 (Boataround-class coverage). Sync was missing this
            // parameter — every existing yacht_equipment row was capped at
            // whatever the slim list emitted.
            val mmkResponse = mmkAuditedClient.getYachts(
                companyId = agencyExternalId,
                inventory = BookingApi.InventoryGetYachts.RAW,
            )
            if (mmkResponse.isEmpty() && agenciesWithoutYachts.contains(agency.id)) {
                log.trace("No Yachts found for ${agency.id}")
                return
            }
            mmkYachtSyncService.syncYachtsForAgency(agency.id!!, mmkResponse)
        } catch (e: Exception) {
            log.error(
                "Error syncing yachts for agency: ${agency.id} ${agency.name}, externalId: $agencyExternalId",
                e,
            )
        }
    }

    fun yachtTranslationsSync() {
        val agencies =
            agencyRepository.findAllActiveByPrimarySyncProviderAndActiveYachts(ExternalSystemEnum.MMK.value.toLong())
        agencies.forEach { agency ->
            val agencyExternalId = agency.getExternalId()
            log.info("Doing translations sync for agency: ${agency.id} ${agency.name} externalId: $agencyExternalId")
            if (agencyExternalId == null) {
                log.error("Agency external id is null for agency: ${agency.id} ${agency.name}")
                return@forEach
            }

            processYachtTranslationsSync(agencyExternalId, agency)
        }
    }

    private fun processYachtTranslationsSync(
        agencyExternalId: Long,
        agency: Agency,
    ) {
        SUPPORTED_LANGUAGES.forEach { lang ->
            val mmkResponse = mmkAuditedClient.getYachts(companyId = agencyExternalId, language = lang)
            val language = LanguageEnum.valueOf(lang.value.uppercase())
            try {
                mmkYachtSyncService.syncYachtsTranslationsForAgency(agency.id!!, mmkResponse, language)
            } catch (e: Exception) {
                log.error(
                    "Error syncing yacht translations for agency: ${agency.id} ${agency.name}, language: $language",
                    e,
                )
            }
        }
    }
}
