package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
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
 * Owner rule 16.10.2026 at sync time (NauSys; MMK has the same one-line create hook): a new boat's slug is frozen once,
 * from its PUBLIC name (R02, YachtNamePolicy) - through YachtRepository.freezePublicSlug, the only writer, which never
 * replaces a frozen slug. A shell row (no model yet) is frozen when its model arrives. An update never freezes.
 */
class NauSysYachtSyncPublicSlugTests {
    private val agency =
        Agency().apply {
            id = 1795L
            name = "Some Agency"
        }
    private val seaBase = 10
    private val seaLocation = Location().apply { id = 40L }
    private val bavaria =
        Model().apply {
            id = 5L
            name = "Bavaria C42"
            manufacturer = Manufacturer().also { it.name = "Bavaria" }
            externalCategoryId = 101L
        }
    private val existing =
        Yacht().apply {
            id = 88L
            name = "Old Name"
            sysActive = true
            model = bavaria
        }

    // tracked earlier as a shell: NauSys had not sent its model yet
    private val shell =
        Yacht().apply {
            id = 89L
            name = "Shell"
            sysActive = false
        }
    private var nextId = 9001L
    private val frozen = mutableListOf<Pair<Long, String>>()

    private fun mapping(
        externalId: Long,
        systemId: Long,
    ) = ExternalMapping(externalId, systemId, null, null, null)

    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByTypeAndExtendedType" -> listOf(mapping(600L, existing.id!!), mapping(602L, shell.id!!))
                "getCachedAllMappingsByType" ->
                    if (inv.arguments[0] == "Location") listOf(mapping(seaBase.toLong(), seaLocation.id!!)) else listOf(mapping(700L, bavaria.id!!))
                else -> null
            }
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
                "findById" -> Optional.ofNullable(listOf(existing, shell).find { inv.arguments[0] == it.id })
                // IDENTITY: the INSERT gives a new boat its id
                "saveAndFlush", "save" -> inv.getArgument<Yacht>(0).also { if (it.id == null) it.id = nextId++ }
                "freezePublicSlug" -> null.also { frozen += inv.getArgument<Long>(0) to inv.getArgument<String>(1) }
                else -> null
            }
        }
    private val models =
        mock(ModelRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(bavaria) else null }
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
            equipmentLinkResolver = any(),
            locationQueryingService = locations,
            externalEquipmentRepository = any(),
            yachtExtraRepository = any(),
            extraRepository = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            fileSystemService = any(),
            agencyRepository = agencies,
            externalSeasonRepository = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        )

    private fun sync(vararg yachtsById: Pair<Long, String>) =
        service.syncYachtsForAgency(
            agency.id!!,
            RestYachtList(
                yachts = yachtsById.map { (id, name) -> RestYacht(id = id, name = name, yachtModelId = 700L, locationId = seaBase, charterType = "BAREBOAT") },
            ),
        )

    @Test
    fun `a new boat is frozen once, from its public name`() {
        sync(603L to "Sunsail 41.3 Premium")

        frozen shouldContainExactly listOf(9001L to "bavaria-c42-boat4you-413-premium-9001")
    }

    @Test
    fun `an update never freezes, whatever the partner renames`() {
        sync(600L to "Brand New Name")

        existing.name shouldBe "Brand New Name"
        frozen shouldContainExactly emptyList()
    }

    @Test
    fun `a shell row is frozen when its model arrives`() {
        sync(602L to "Shell")

        shell.model shouldBe bavaria
        frozen shouldContainExactly listOf(89L to "bavaria-c42-shell-89")
    }
}
