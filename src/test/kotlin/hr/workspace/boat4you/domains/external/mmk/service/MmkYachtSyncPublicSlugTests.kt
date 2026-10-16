package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.catalouge.services.ModelQueryingService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.Product
import java.util.Optional

/**
 * Owner rule 16.10.2026 at sync time (MMK; NauSysYachtSyncPublicSlugTests is the NauSys twin): a new boat's slug is
 * frozen once, after the INSERT gave it its id, from its PUBLIC name (R02, YachtNamePolicy) - through
 * YachtRepository.freezePublicSlug, the only writer. An update never freezes. A mapping whose boat row is gone is
 * relinked to the boat re-created for it, so the next sync updates that boat instead of creating another (new id = new
 * URL). Repositories are answer-by-method-name mocks.
 */
class MmkYachtSyncPublicSlugTests {
    private val seaBase = 1L
    private val agency =
        Agency().apply {
            id = 1795L
            name = "Some Agency"
        }
    private val seaLocation = Location().apply { id = 40L }
    private val bavaria =
        Model().apply {
            id = 5L
            name = "Bavaria C42"
            manufacturer = Manufacturer().also { it.name = "Bavaria" }
        }
    private val existing =
        Yacht().apply {
            id = 88L
            name = "Old Name"
            sysActive = true
            model = bavaria
        }
    private val goneMapping = mapping(504L, 999L)
    private var nextId = 9001L
    private val frozen = mutableListOf<Pair<Long, String>>()
    private val savedMappings = mutableListOf<ExternalMapping>()

    private fun mapping(
        externalId: Long,
        systemId: Long,
    ) = ExternalMapping(externalId, systemId, null, null, null)

    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                // 500 = our boat 88; 504 = a boat row that no longer exists
                "getAllMappingsByTypeAndExtendedType" -> listOf(mapping(500L, existing.id!!), goneMapping)
                "getCachedAllMappingsByType" -> listOf(mapping(seaBase, seaLocation.id!!))
                "getAllMappingsByType" -> emptyList<ExternalMapping>()
                else -> null
            }
        }
    private val mappingRepository =
        mock(ExternalMappingRepository::class.java) { inv ->
            if (inv.method.name == "save") inv.getArgument<ExternalMapping>(0).also { savedMappings += it } else null
        }
    private val locations =
        mock(LocationQueryingService::class.java) { inv ->
            when (inv.method.name) {
                "getInlandLocationExternalIds" -> emptySet<Long>()
                "getCachedLocationById" -> seaLocation
                else -> null
            }
        }
    private val yachts =
        mock(YachtRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.ofNullable(listOf(existing).find { inv.arguments[0] == it.id })
                // IDENTITY: the INSERT gives a new boat its id
                "save", "saveAndFlush" -> inv.getArgument<Yacht>(0).also { if (it.id == null) it.id = nextId++ }
                "freezePublicSlug" -> null.also { frozen += inv.getArgument<Long>(0) to inv.getArgument<String>(1) }
                "findAllByAgencyAndExternalIdNotIn" -> emptyList<Yacht>()
                else -> null
            }
        }
    private val models =
        mock(ModelQueryingService::class.java) { inv -> if (inv.method.name == "findModelByExternalIdAndExternalSystem") bavaria else null }
    private val agencies =
        mock(AgencyRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(agency) else null }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        MmkYachtSyncService(
            yachtRepository = yachts,
            externalMappingRepository = mappingRepository,
            externalSystemService = systems,
            modelRepository = any(),
            externalMappingService = mappings,
            yachtImageRepository = any(),
            manufacturerRepository = any(),
            locationQueryingService = locations,
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            modelQueryingService = models,
            externalEquipmentRepository = any(),
            extraRepository = any(),
            yachtExtraRepository = any(),
            fileSystemService = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            agencyRepository = agencies,
            modelNameNormaliser = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private fun sync(vararg yachtsById: Pair<Long, String>) =
        service.syncYachtsForAgency(
            agency.id!!,
            yachtsById.map { (id, name) ->
                org.openapitools.client.mmk.model.Yacht(
                    id = id,
                    name = name,
                    kind = "Sail boat",
                    homeBaseId = seaBase,
                    homeBase = "Base",
                    companyId = 1L,
                    company = "Company",
                    modelId = 700L,
                    products = listOf(Product(name = "bareboat", extras = emptyList())),
                )
            },
        )

    @Test
    fun `a new boat is frozen once, from its public name, after the INSERT gave it its id`() {
        sync(503L to "Sunsail 41.3 Premium")

        frozen shouldContainExactly listOf(9001L to "bavaria-c42-boat4you-413-premium-9001")
        savedMappings.map { it.externalId to it.systemId } shouldContainExactly listOf(503L to 9001L)
    }

    @Test
    fun `an update never freezes, whatever the partner renames`() {
        sync(500L to "Brand New Name")

        existing.name shouldBe "Brand New Name"
        frozen shouldContainExactly emptyList()
        savedMappings shouldContainExactly emptyList()
    }

    @Test
    fun `a mapping whose boat row is gone - the re-created boat is frozen and the mapping relinked to it`() {
        sync(504L to "Lost Row")

        frozen shouldContainExactly listOf(9001L to "bavaria-c42-lost-row-9001")
        goneMapping.systemId shouldBe 9001L
        savedMappings shouldContainExactly listOf(goneMapping)
    }
}
