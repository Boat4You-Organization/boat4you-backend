package hr.workspace.boat4you.domains.external.dev

import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import hr.workspace.boat4you.domains.external.mmk.service.MmkCatalogueIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtIntegrationService
import hr.workspace.boat4you.domains.external.nausys.job.NausysSyncJob
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueIntegrationService
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Dev-only catalog sync trigger so the admin can refresh `external_equipment`
 * (and re-sync yacht_equipment afterwards) from the partner equipment
 * endpoints in a single curl. Production sync runs through the
 * SYSTEM_ADMIN-protected /admin/{partner}/sync controllers.
 *
 * Triple-defense hardening (F1-041 / F3-035), 2026-05-11:
 * 1. `@Profile("dev")` — bean is not registered unless dev profile is active
 *    (was the only guard previously).
 * 2. `/admin/dev` URL prefix — does not match Spring Security's
 *    `permitAll` on the `/public` path tree, so even if a misconfigured prod ever
 *    loaded this bean, JWT auth would still be required (analogous to the
 *    /admin/nausys + /admin/mmk hardening on 23.4.2026, see SecurityConfiguration).
 * 3. `@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")` at class level —
 *    role check on top of authentication.
 * 4. State-changing operations are `@PostMapping` so they can't be invoked
 *    by the browser address bar / accidental link, and CSRF posture is
 *    consistent with the rest of the admin surface.
 *
 * Diagnostics that only read partner state remain `@GetMapping` because
 * they have no side-effect on our DB. Pulls the entire MMK + NauSys
 * equipment catalog (one-shot upserts) so the yacht-level sync can finally
 * find an `external_equipment` record for every partner-emitted
 * equipmentId. Without this the yacht_equipment table only ever contained
 * ~8 items per yacht — the rest were silently skipped because the mapping
 * rows didn't exist.
 */
@RestController
@Profile("dev")
@RequestMapping("/admin/dev")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
class DevEquipmentSyncController(
    private val mmkCatalogueIntegrationService: MmkCatalogueIntegrationService,
    private val nauSysCatalogueIntegrationService: NauSysCatalogueIntegrationService,
    private val mmkYachtIntegrationService: MmkYachtIntegrationService,
    private val nausysSyncJob: NausysSyncJob,
    private val yachtRepository: YachtRepository,
    private val cacheManager: CacheManager,
    private val mmkAuditedClient: MmkAuditedClient,
    private val nauSysAuditedClient: hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient,
    private val nauSysAuthProvider: hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider,
    private val externalEquipmentRepository: hr.workspace.boat4you.domains.catalouge.jpa.ExternalEquipmentRepository,
) {
    /**
     * Diagnostic — fetches the live NauSys yacht catalogue for a charter
     * company and reports the standardYachtEquipment list size for the
     * requested yachtId. Confirms whether NauSys actually ships more than
     * the ~16 items we persist per yacht, or whether the partner payload is
     * genuinely that thin (the latter limits how much we can render
     * compared to competitors who augment with their own enrichment).
     */
    @GetMapping("/nausys-yacht-equipment/{companyId}/{yachtId}")
    fun nauSysYachtEquipment(
        @PathVariable companyId: Long,
        @PathVariable yachtId: Long,
    ): Map<String, Any?> {
        val auth = nauSysAuthProvider.auth
        val request =
            org.openapitools.client.nausys.model.AllYachtsRequest(
                username = auth.username!!,
                password = auth.password!!,
            )
        val response = nauSysAuditedClient.allYachts(companyId, request)
        val yachts = response.yachts ?: emptyList()
        val yacht = yachts.firstOrNull { it.id?.toLong() == yachtId }
        val eqs = yacht?.standardYachtEquipment ?: emptyList()
        val catalogue =
            externalEquipmentRepository.getCachedByExternalSystemId(
                hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum.NAUSYS.value,
            )
        return mapOf(
            "found" to (yacht != null),
            "totalYachtsForCompany" to yachts.size,
            "yachtId" to yachtId,
            "yachtName" to yacht?.name,
            "equipmentCount" to eqs.size,
            "equipment" to
                eqs.take(80).map { item ->
                    val name = catalogue.firstOrNull { it.externalId == item.equipmentId?.toLong() }?.name
                    mapOf(
                        "equipmentId" to item.equipmentId,
                        "name" to name,
                        "quantity" to item.quantity,
                        "highlight" to item.highlight,
                    )
                },
        )
    }

    /**
     * Diagnostic — fetches the live MMK yacht catalog and reports the
     * equipment-list size for the requested companyId. Lets us prove
     * whether MMK actually emits more equipment per yacht than what we
     * persist (vs. the partner just having a thin payload). Pass the
     * MMK-side companyId of the agency that owns the yacht, plus the
     * MMK yacht id to inspect.
     */
    @GetMapping("/mmk-yacht-equipment/{companyId}/{yachtId}")
    fun mmkYachtEquipment(
        @PathVariable companyId: Long,
        @PathVariable yachtId: Long,
    ): Map<String, Any?> {
        val yachts = mmkAuditedClient.getYachts(companyId = companyId)
        val yacht = yachts.firstOrNull { it.id?.toLong() == yachtId }
        val eqs = yacht?.equipment ?: emptyList()
        return mapOf(
            "found" to (yacht != null),
            "totalYachtsForCompany" to yachts.size,
            "yachtId" to yachtId,
            "equipmentCount" to eqs.size,
            "equipment" to eqs.take(40).map { mapOf("id" to it.id, "value" to it.value) },
        )
    }

    /**
     * Clear ALL Spring caches in one call. Needed after DB writes that bypass
     * the @CacheEvict-annotated mutation paths — e.g. region/location rows
     * inserted via psql or Flyway migrations don't trigger eviction, so the
     * autocomplete sees the pre-write snapshot until the TTL expires (or the
     * app restarts). Hit this once after such writes and every cache resets.
     */
    @PostMapping("/clear-caches")
    fun clearCaches(): Map<String, Any> {
        val names = cacheManager.cacheNames.toList()
        names.forEach { cacheManager.getCache(it)?.clear() }
        return mapOf("status" to "ok", "cleared" to names)
    }

    @PostMapping("/sync-equipment-catalog")
    fun syncEquipmentCatalog(): Map<String, String> {
        mmkCatalogueIntegrationService.equipmentSync()
        nauSysCatalogueIntegrationService.equipmentSync()
        // Belt-and-braces: equipmentSync is annotated with @CacheEvict but
        // explicitly clear here too in case Spring's proxy chain swallows
        // the eviction (bean called from within the same class, etc.).
        cacheManager.getCache("externalEquipmentCache")?.clear()
        return mapOf("status" to "ok", "message" to "MMK + NauSys equipment catalogs synced + cache evicted")
    }

    /**
     * Re-runs the full NauSys catalogue: manufacturers + models + equipment.
     * Needed after the 27.4. Manufacturer/Model dedup migration that left
     * 866 orphan `external_mapping(type=Model, externalSystem=NAUSYS)` rows
     * pointing at deleted Model ids — those orphans cause yacht sync to skip
     * ~half of NauSys yachts (their `yachtModelId` resolves to nothing).
     * Running modelsSync recreates the missing Model rows from the partner
     * catalogue, after which yacht sync can resolve them again.
     */
    @PostMapping("/sync-catalogue")
    fun syncCatalogue(): Map<String, String> {
        nauSysCatalogueIntegrationService.manufacturerSync()
        nauSysCatalogueIntegrationService.modelsSync()
        nauSysCatalogueIntegrationService.equipmentSync()
        cacheManager.getCache("externalEquipmentCache")?.clear()
        return mapOf("status" to "ok", "message" to "NauSys catalogue (manufacturer + model + equipment) synced")
    }

    /**
     * Re-runs yacht-level sync for both partners. Long-running (~minutes
     * for 12k yachts). Picks up the freshly-populated `external_equipment`
     * mappings so yacht_equipments now stores every partner-emitted item
     * instead of dropping ~70% to "no mapping found".
     */
    @PostMapping("/resync-yachts")
    fun resyncYachts(): Map<String, String> {
        mmkYachtIntegrationService.yachtSync()
        nausysSyncJob.runYachtSync()
        return mapOf("status" to "ok", "message" to "MMK + NauSys yacht sync completed")
    }
}
