package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestYacht
import org.openapitools.client.nausys.model.RestYachtList
import java.util.Optional

/**
 * 25.9.2026 sea charter only: a NauSys yacht whose model is built by an inland-only builder (Linssen) is never
 * imported, and one imported earlier is switched off (row and mapping kept). Nothing past the skip may run - it would
 * NPE on the empty location list.
 */
class NauSysYachtSyncInlandSkipTests {
    private val agency =
        Agency().apply {
            id = 1795L
            name = "Hibo Yachtcharter"
        }
    private val existing =
        Yacht().apply {
            id = 88L
            name = "Grand Sturdy 35.0"
            sysActive = true
        }

    // category 101 = MOTOR_YACHT: the vessel type passes, only the builder gives it away
    private val linssenModel =
        Model().apply {
            id = 5L
            name = "Grand Sturdy 35.0"
            manufacturer = Manufacturer().also { it.name = "Linssen" }
            externalCategoryId = 101L
        }
    private var model = linssenModel
    private val saved = mutableListOf<Yacht>()

    private fun mapping(
        externalId: Long,
        systemId: Long,
    ) = ExternalMapping(externalId, systemId, null, null, null)

    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByTypeAndExtendedType" -> listOf(mapping(600L, existing.id!!))
                // Model mappings (model 700 -> Linssen); the Location mappings are never reached
                "getCachedAllMappingsByType" -> listOf(mapping(700L, linssenModel.id!!))
                else -> null
            }
        }
    private val yachts =
        mock(YachtRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.ofNullable(existing.takeIf { inv.arguments[0] == it.id })
                "saveAndFlush", "save" -> inv.getArgument<Yacht>(0).also { saved += it }
                else -> null
            }
        }
    private val models =
        mock(ModelRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(model) else null }
    private val agencies =
        mock(AgencyRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(agency) else null }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        NauSysYachtSyncService(
            yachtRepository = yachts,
            externalMappingRepository = any(),
            externalSystemService = systems,
            modelRepository = models,
            externalMappingService = mappings,
            yachtImageRepository = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentRepository = any(),
            locationQueryingService = any(),
            externalEquipmentRepository = any(),
            yachtExtraRepository = any(),
            extraRepository = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            fileSystemService = any(),
            agencyRepository = agencies,
            externalSeasonRepository = any(),
        )

    private fun nausysYachts(vararg ids: Long) = RestYachtList(yachts = ids.map { RestYacht(id = it, name = "Yacht $it", yachtModelId = 700L) })

    @Test
    fun `an already imported inland yacht is switched off, a new one is not imported`() {
        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L, 601L))

        existing.sysActive shouldBe false
        saved shouldContainExactly listOf(existing)
    }

    @Test
    fun `a model without a manufacturer is caught by its name`() {
        // Kuhnle-Tours' Kormoran: manufacturer never resolved, the model name still names the builder
        model =
            Model().apply {
                id = 5L
                name = "Kormoran 1140"
                externalCategoryId = 101L
            }

        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L, 601L))

        existing.sysActive shouldBe false
        saved shouldContainExactly listOf(existing)
    }

    @Test
    fun `an inland yacht already off is left alone`() {
        existing.sysActive = false

        service.syncYachtsForAgency(agency.id!!, nausysYachts(600L))

        saved shouldContainExactly emptyList()
    }
}
