package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateWrapper
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import org.openapitools.client.nausys.model.RestFreeYachtsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

/** One obligatory service NauSys returns once the selected extras are applied. */
data class NausysObligatoryExtra(
    val serviceId: Long,
    val name: String,
    val totalPrice: BigDecimal,
    val calculationType: String?,
)

/**
 * Live "what becomes obligatory if these services are added" probe against NauSys.
 *
 * NauSys applies per-agency rules at quote time — e.g. Navigare makes the Damage
 * Waiver obligatory once a Skipper is added. That rule is NOT in the static
 * catalogue (the DW service is obligatory=false there); it only surfaces when
 * getFreeYachts is called WITH the chosen serviceIDs. We replay exactly that call
 * and return the obligatory services so the price preview can auto-include them —
 * the same truth the partner returns at option creation. Verified 1.6.2026 on
 * Lagoon 38 "Dschubba": serviceIDs=[1 skipper] -> DW (590058) comes back obligatory.
 */
@Service
class NauSysObligatoryExtrasService(
    private val nauSysRetryableClient: NauSysRetryableClient,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val externalMappingService: ExternalMappingService,
    private val externalSystemService: ExternalSystemService,
    private val externalEquipmentRepository: ExternalEquipmentRepository,
    private val yachtExtraRepository: YachtExtraRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * For a NauSys yacht, returns the services NauSys marks obligatory when the
     * currently selected extras are added (handling/preparation that are always
     * obligatory included — the caller dedupes those against the base price calc).
     * Empty list for non-NauSys yachts, no selection, or any failure (best-effort:
     * the price preview must never break because the partner is slow/down).
     */
    fun obligatoryWithSelectedServices(
        yacht: Yacht,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        selectedExtras: Set<String>,
    ): List<NausysObligatoryExtra> {
        if (selectedExtras.isEmpty() || yacht.id == null) return emptyList()
        return runCatching {
            val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
            val extYachtId =
                externalMappingService
                    .findBySystemIdAndExternalSystemAndType(
                        yacht.id!!,
                        externalSystem,
                        Yacht::class.simpleName.toString(),
                    )?.externalId ?: return emptyList()

            val nausysServices =
                externalEquipmentRepository
                    .getCachedByExternalSystemId(ExternalSystemEnum.NAUSYS.value)
                    .filter { it.type == ExternalEquipmentType.SERVICE && it.externalId != null && it.name != null }
            val serviceIdByName = nausysServices.associate { it.name!! to it.externalId!! }
            val nameByServiceId = nausysServices.associate { it.externalId!! to it.name!! }

            // Selected extras -> NauSys serviceIDs. Only services carry a serviceId
            // (equipment uses equipmentId and does not drive obligatory rules here).
            val selectedServiceIds =
                yachtExtraRepository
                    .findAllByYacht(yacht)
                    .filter { selectedExtras.contains(it.extrasKey()) }
                    .mapNotNull { ye -> ye.name?.let { serviceIdByName[it] }?.toInt() }
                    .distinct()
            if (selectedServiceIds.isEmpty()) return emptyList()

            val request =
                RestFreeYachtsRequest(
                    credentials = nauSysAuthProvider.auth,
                    periodFrom = NauSysDateWrapper(dateFrom.format(NauSysDateWrapper.DATE_FORMATTER)),
                    periodTo = NauSysDateWrapper(dateTo.format(NauSysDateWrapper.DATE_FORMATTER)),
                    yachts = listOf(extYachtId),
                    serviceIDs = selectedServiceIds,
                    extendedDataSet = "OBLIGATORY_SERVICES,ADDITIONAL_EXTRAS",
                    ignoreOptions = true,
                )

            val freeYacht =
                nauSysRetryableClient.getFreeYachts(request).freeYachts?.firstOrNull()
                    ?: return emptyList()

            (freeYacht.obligatoryExtras ?: emptyList()).mapNotNull { o ->
                val sid = o.serviceId ?: return@mapNotNull null
                NausysObligatoryExtra(
                    serviceId = sid,
                    name = nameByServiceId[sid] ?: "",
                    totalPrice = o.totalPrice?.toBigDecimal() ?: BigDecimal.ZERO,
                    calculationType = o.calculationType?.value,
                )
            }
        }.getOrElse {
            log.warn("NauSys obligatory re-quote failed for yacht=${yacht.id}: ${it.message}")
            emptyList()
        }
    }
}
